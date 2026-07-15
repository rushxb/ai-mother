package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceCopyResult;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.RollbackRestore;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Restores an opt-in rollback snapshot after a failed generation attempt.
 */
@Slf4j
@Component
public class GenerationRollbackRestoreService {

    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationSnapshotWorkspaceService snapshotWorkspaceService;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final SnapshotNamePolicy snapshotNamePolicy;

    public GenerationRollbackRestoreService(
            GenerationWorkspaceService generationWorkspaceService,
            GenerationSnapshotWorkspaceService snapshotWorkspaceService,
            WorkspaceFileSystemService workspaceFileSystemService,
            SnapshotNamePolicy snapshotNamePolicy
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
    }

    public GenerationArtifact restoreIfAllowed(Long appId,
                                               String taskId,
                                               GenerationArtifact changePlanArtifact,
                                               GenerationArtifact rollbackPointArtifact) {
        RollbackRestore restore = restore(appId, taskId, changePlanArtifact, rollbackPointArtifact);
        return GenerationArtifact.of("rollback_restore", "Orchestrator", "Rollback restore", restore.toPayload());
    }

    RollbackRestore restore(Long appId,
                            String taskId,
                            GenerationArtifact changePlanArtifact,
                            GenerationArtifact rollbackPointArtifact) {
        ChangePlan changePlan = ChangePlan.fromPayload(payload(changePlanArtifact));
        String rollbackStrategy = changePlan == null ? "manual_retry_without_snapshot" : changePlan.rollbackStrategy();
        if (changePlan == null || !changePlan.requiresSnapshotRollback()) {
            return RollbackRestore.skipped(appId, taskId, rollbackStrategy, "", "", "rollback_strategy_not_snapshot");
        }
        Map<String, Object> rollbackPayload = payload(rollbackPointArtifact);
        String rollbackStatus = stringValue(rollbackPayload.get("status"));
        String snapshotName = stringValue(rollbackPayload.get("snapshotName"));
        String snapshotPathValue = stringValue(rollbackPayload.get("snapshotPath"));
        String projectPathValue = stringValue(rollbackPayload.get("projectPath"));
        if (!"created".equals(rollbackStatus)) {
            return RollbackRestore.skipped(
                    appId,
                    taskId,
                    rollbackStrategy,
                    snapshotPathValue,
                    projectPathValue,
                    "rollback_point_not_created"
            );
        }
        if (snapshotName.isBlank() || snapshotPathValue.isBlank() || projectPathValue.isBlank()) {
            return RollbackRestore.skipped(
                    appId,
                    taskId,
                    rollbackStrategy,
                    snapshotPathValue,
                    projectPathValue,
                    "rollback_point_path_missing"
            );
        }

        String sourceTypeValue = stringValue(rollbackPayload.get("sourceType"));
        CodeGenTypeEnum sourceType = CodeGenTypeEnum.getEnumByValue(sourceTypeValue);
        if (!matchesArtifactContext(appId, taskId, rollbackPayload, sourceType)) {
            return RollbackRestore.skipped(
                    appId,
                    taskId,
                    rollbackStrategy,
                    snapshotPathValue,
                    projectPathValue,
                    "rollback_artifact_context_mismatch"
            );
        }

        Path snapshotPath;
        Path projectPath;
        try {
            snapshotPath = snapshotWorkspaceService.resolveReportedSnapshot(appId, snapshotName, snapshotPathValue);
            GenerationWorkspace workspace = generationWorkspaceService.resolve(appId, sourceType);
            projectPath = Path.of(projectPathValue).toAbsolutePath().normalize();
            if (!projectPath.equals(workspace.canonicalRootPath())) {
                throw new IllegalArgumentException("project path mismatch");
            }
        } catch (RuntimeException exception) {
            return RollbackRestore.skipped(
                    appId,
                    taskId,
                    rollbackStrategy,
                    snapshotPathValue,
                    projectPathValue,
                    "rollback_path_out_of_root"
            );
        }

        Path backupPath;
        try {
            backupPath = backupPath(appId, taskId);
        } catch (RuntimeException exception) {
            return RollbackRestore.skipped(
                    appId,
                    taskId,
                    rollbackStrategy,
                    snapshotPathValue,
                    projectPathValue,
                    "rollback_path_out_of_root"
            );
        }
        try {
            if (!workspaceFileSystemService.isDirectory(snapshotPath)) {
                return RollbackRestore.skipped(
                        appId, taskId, rollbackStrategy, snapshotPathValue, projectPathValue, "snapshot_missing"
                );
            }
            if (!workspaceFileSystemService.isDirectory(projectPath.getParent())) {
                return RollbackRestore.skipped(
                        appId, taskId, rollbackStrategy, snapshotPathValue, projectPathValue, "project_parent_missing"
                );
            }
            String backupPathValue = "";
            boolean projectExists = workspaceFileSystemService.isDirectory(projectPath);
            if (projectExists) {
                workspaceFileSystemService.copyDirectory(projectPath, backupPath);
                backupPathValue = backupPath.toString();
            }
            WorkspaceCopyResult restoreResult = projectExists
                    ? workspaceFileSystemService.replaceDirectory(snapshotPath, projectPath)
                    : workspaceFileSystemService.copyDirectory(snapshotPath, projectPath);
            return RollbackRestore.restored(
                    appId,
                    taskId,
                    rollbackStrategy,
                    snapshotPath.toString(),
                    projectPath.toString(),
                    backupPathValue,
                    restoreResult.fileCount()
            );
        } catch (Exception exception) {
            log.warn("Failed to restore rollback snapshot, appId: {}, taskId: {}, exceptionType: {}",
                    appId, taskId, exception.getClass().getSimpleName());
            return RollbackRestore.failed(
                    appId,
                    taskId,
                    rollbackStrategy,
                    snapshotPath.toString(),
                    projectPath.toString(),
                    backupPath.toString(),
                    "rollback_restore_failed"
            );
        }
    }

    private Path backupPath(Long appId, String taskId) {
        String backupName = snapshotNamePolicy.createTaskScopedName("failed_generation", taskId);
        return snapshotWorkspaceService.resolveSnapshot(appId, backupName);
    }

    private Map<String, Object> payload(GenerationArtifact artifact) {
        return artifact == null || artifact.payload() == null ? Map.of() : artifact.payload();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean matchesArtifactContext(Long appId,
                                           String taskId,
                                           Map<String, Object> rollbackPayload,
                                           CodeGenTypeEnum sourceType) {
        return appId != null
                && appId > 0
                && taskId != null
                && !taskId.isBlank()
                && String.valueOf(appId).equals(stringValue(rollbackPayload.get("appId")))
                && taskId.equals(stringValue(rollbackPayload.get("taskId")))
                && sourceType != null;
    }
}
