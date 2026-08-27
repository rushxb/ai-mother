package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemException;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceCopyResult;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.RollbackPoint;
import com.rush.rushaicodemother.orchestration.artifact.RollbackRestore;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Objects;

/** 在生成失败后恢复经过完整 provenance 校验的回滚快照。 */
@Slf4j
@Component
public class GenerationRollbackRestoreService {

    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationSnapshotWorkspaceService snapshotWorkspaceService;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final SnapshotNamePolicy snapshotNamePolicy;
    private final GenerationTaskFenceGuard generationTaskFenceGuard;
    private final GenerationExecutionContextService executionContextService;

    public GenerationRollbackRestoreService(
            GenerationWorkspaceService generationWorkspaceService,
            GenerationSnapshotWorkspaceService snapshotWorkspaceService,
            WorkspaceFileSystemService workspaceFileSystemService,
            SnapshotNamePolicy snapshotNamePolicy,
            GenerationTaskFenceGuard generationTaskFenceGuard,
            GenerationExecutionContextService executionContextService
    ) {
        this.generationWorkspaceService = Objects.requireNonNull(
                generationWorkspaceService,
                "generationWorkspaceService must not be null"
        );
        this.snapshotWorkspaceService = Objects.requireNonNull(
                snapshotWorkspaceService,
                "snapshotWorkspaceService must not be null"
        );
        this.workspaceFileSystemService = Objects.requireNonNull(
                workspaceFileSystemService,
                "workspaceFileSystemService must not be null"
        );
        this.snapshotNamePolicy = Objects.requireNonNull(snapshotNamePolicy, "snapshotNamePolicy must not be null");
        this.generationTaskFenceGuard = Objects.requireNonNull(
                generationTaskFenceGuard,
                "generationTaskFenceGuard must not be null"
        );
        this.executionContextService = Objects.requireNonNull(
                executionContextService,
                "executionContextService must not be null"
        );
    }

    public GenerationArtifact restoreIfAllowed(Long appId,
                                               String taskId,
                                               GenerationArtifact changePlanArtifact,
                                               GenerationArtifact rollbackPointArtifact) {
        RollbackRestore restore = restore(appId, taskId, changePlanArtifact, rollbackPointArtifact);
        return restore.toArtifact();
    }

    RollbackRestore restore(Long appId,
                            String taskId,
                            GenerationArtifact changePlanArtifact,
                            GenerationArtifact rollbackPointArtifact) {
        ChangePlan changePlan;
        try {
            changePlan = changePlanArtifact == null ? null : ChangePlan.fromArtifact(changePlanArtifact);
        } catch (IllegalArgumentException invalidArtifact) {
            return skipped(appId, taskId, "manual_retry_without_snapshot", "change_plan_invalid");
        }
        String rollbackStrategy = changePlan == null
                ? "manual_retry_without_snapshot"
                : changePlan.rollbackStrategy();
        if (changePlan == null || !changePlan.requiresSnapshotRollback()) {
            return skipped(appId, taskId, rollbackStrategy, "rollback_strategy_not_snapshot");
        }
        if (rollbackPointArtifact == null) {
            return skipped(appId, taskId, rollbackStrategy, "rollback_point_not_created");
        }

        RollbackPoint rollbackPoint;
        try {
            rollbackPoint = RollbackPoint.fromArtifact(rollbackPointArtifact, appId, taskId);
        } catch (IllegalArgumentException exception) {
            log.warn("Rollback point artifact validation failed, appId: {}, taskId: {}, exceptionType: {}",
                    appId, taskId, exception.getClass().getSimpleName());
            return skipped(appId, taskId, rollbackStrategy, "rollback_artifact_invalid");
        }
        if (!rollbackPoint.created()) {
            return RollbackRestore.skipped(
                    appId,
                    taskId,
                    rollbackStrategy,
                    rollbackPoint.snapshotPath(),
                    rollbackPoint.projectPath(),
                    "rollback_point_not_created"
            );
        }
        if (!rollbackPoint.trustedForSnapshotConsumption()) {
            return RollbackRestore.skipped(
                    appId,
                    taskId,
                    rollbackStrategy,
                    rollbackPoint.snapshotPath(),
                    rollbackPoint.projectPath(),
                    "rollback_snapshot_identity_unsupported"
            );
        }

        CodeGenTypeEnum sourceType = CodeGenTypeEnum.getEnumByValue(rollbackPoint.sourceType());
        SnapshotScope scope;
        GenerationWorkspace workspace;
        Path projectPath;
        SnapshotSelector selector;
        try {
            if (sourceType == null) {
                throw new IllegalArgumentException("source type is unsupported");
            }
            scope = new SnapshotScope(appId, sourceType, rollbackPoint.scope());
            if (!".".equals(scope.relativePath())) {
                throw new IllegalArgumentException("automatic rollback only supports the project root scope");
            }
            workspace = generationWorkspaceService.resolve(appId, sourceType);
            projectPath = Path.of(rollbackPoint.projectPath()).toAbsolutePath().normalize();
            if (!projectPath.equals(workspace.canonicalRootPath())) {
                throw new IllegalArgumentException("project path mismatch");
            }
            selector = SnapshotSelector.persisted(
                    rollbackPoint.snapshotName(),
                    scope,
                    rollbackPoint.snapshotId(),
                    SnapshotKind.ROLLBACK_POINT,
                    taskId,
                    rollbackPoint.executionEpoch(),
                    rollbackPoint.manifestSha256()
            );
        } catch (RuntimeException exception) {
            return RollbackRestore.skipped(
                    appId,
                    taskId,
                    rollbackStrategy,
                    rollbackPoint.snapshotPath(),
                    rollbackPoint.projectPath(),
                    "rollback_path_out_of_root"
            );
        }

        StoredSnapshot snapshot;
        String backupPathValue = "";
        try {
            generationTaskFenceGuard.assertCurrent(taskId);
            long currentEpoch = executionContextService.getExecutionFence(taskId)
                    .orElseThrow(() -> new GenerationExecutionPolicyException(
                            "generation execution fence does not exist for rollback restore"))
                    .executionEpoch();
            if (currentEpoch != rollbackPoint.executionEpoch()) {
                throw new GenerationExecutionPolicyException(
                        "rollback point belongs to a different generation execution epoch");
            }
            Runnable continuationCheck = () -> executionContextService.assertCanContinue(taskId);

            // 在任何目标目录位移前完成 manifest、scope、任务、epoch 与 payload 摘要校验。
            snapshot = snapshotWorkspaceService.requireSnapshot(selector);
            if (!workspaceFileSystemService.isDirectory(projectPath.getParent())) {
                return RollbackRestore.skipped(
                        appId,
                        taskId,
                        rollbackStrategy,
                        snapshot.containerPath().toString(),
                        projectPath.toString(),
                        "project_parent_missing"
                );
            }

            if (workspaceFileSystemService.isDirectory(projectPath)) {
                String backupName = snapshotNamePolicy.createTaskScopedName("failed_generation", taskId);
                StoredSnapshot backup = snapshotWorkspaceService.captureOrReuse(
                        new SnapshotCapture(
                                backupName,
                                scope,
                                projectPath,
                                SnapshotKind.FAILED_GENERATION_BACKUP,
                                taskId,
                                currentEpoch
                        ),
                        continuationCheck
                );
                backupPathValue = backup.containerPath().toString();
            }

            generationTaskFenceGuard.assertCurrent(taskId);
            WorkspaceCopyResult restoreResult = snapshotWorkspaceService.restore(
                    SnapshotSelector.exact(snapshot),
                    projectPath,
                    continuationCheck
            );
            return RollbackRestore.restored(
                    appId,
                    taskId,
                    rollbackStrategy,
                    snapshot.snapshotName(),
                    snapshot.snapshotId(),
                    snapshot.manifestSha256(),
                    snapshot.scope().relativePath(),
                    snapshot.creatorExecutionEpoch(),
                    snapshot.containerPath().toString(),
                    projectPath.toString(),
                    backupPathValue,
                    restoreResult.fileCount()
            );
        } catch (GenerationExecutionPolicyException staleExecution) {
            throw staleExecution;
        } catch (WorkspaceFileSystemException workspaceFailure) {
            String failureReason = workspaceFailure.reason()
                    == WorkspaceFileSystemException.Reason.REPLACE_OUTCOME_UNKNOWN
                    ? "rollback_restore_outcome_unknown"
                    : "rollback_restore_failed";
            log.warn("Failed to restore rollback snapshot, appId: {}, taskId: {}, reason: {}, exceptionType: {}",
                    appId, taskId, failureReason, workspaceFailure.getClass().getSimpleName());
            return failed(rollbackPoint, rollbackStrategy, backupPathValue, failureReason);
        } catch (Exception exception) {
            log.warn("Failed to restore rollback snapshot, appId: {}, taskId: {}, exceptionType: {}",
                    appId, taskId, exception.getClass().getSimpleName());
            String reason = exception instanceof SnapshotStoreException
                    ? "rollback_snapshot_validation_failed"
                    : "rollback_restore_failed";
            return failed(rollbackPoint, rollbackStrategy, backupPathValue, reason);
        }
    }

    private RollbackRestore skipped(Long appId, String taskId, String strategy, String reason) {
        return RollbackRestore.skipped(appId, taskId, strategy, "", "", reason);
    }

    private RollbackRestore failed(RollbackPoint rollbackPoint,
                                   String rollbackStrategy,
                                   String backupPath,
                                   String reason) {
        return RollbackRestore.failed(
                rollbackPoint.appId(),
                rollbackPoint.taskId(),
                rollbackStrategy,
                rollbackPoint.snapshotName(),
                rollbackPoint.snapshotId(),
                rollbackPoint.manifestSha256(),
                rollbackPoint.scope(),
                rollbackPoint.executionEpoch(),
                rollbackPoint.snapshotPath(),
                rollbackPoint.projectPath(),
                backupPath,
                reason
        );
    }
}
