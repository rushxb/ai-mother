package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceCopyResult;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.RollbackPoint;
import com.rush.rushaicodemother.orchestration.artifact.RollbackRestore;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 在尝试生成失败后恢复选择加入的回滚快照。
 */
@Slf4j
@Component
public class GenerationRollbackRestoreService {

    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationSnapshotWorkspaceService snapshotWorkspaceService;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final SnapshotNamePolicy snapshotNamePolicy;
    private final GenerationTaskFenceGuard generationTaskFenceGuard;

    /**
 * 创建生成回滚恢复服务实例并完成必要的依赖和初始状态设置。
 *
 * @param generationWorkspaceService 生成工作区服务
 * @param snapshotWorkspaceService 快照工作区服务
 * @param workspaceFileSystemService 处理该职责的领域服务
 * @param snapshotNamePolicy 快照名称策略
 * @param generationTaskFenceGuard 生成任务围栏防护
 */
    public GenerationRollbackRestoreService(
            GenerationWorkspaceService generationWorkspaceService,
            GenerationSnapshotWorkspaceService snapshotWorkspaceService,
            WorkspaceFileSystemService workspaceFileSystemService,
            SnapshotNamePolicy snapshotNamePolicy,
            GenerationTaskFenceGuard generationTaskFenceGuard
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
                generationTaskFenceGuard, "generationTaskFenceGuard must not be null");
    }

    /**
 * 返回恢复{@code If}{@code Allowed}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param changePlanArtifact {@code changePlanArtifact} 对应的调用参数
 * @param rollbackPointArtifact 回滚点制品
 * @return 生成回滚恢复
 */
    public GenerationArtifact restoreIfAllowed(Long appId,
                                               String taskId,
                                               GenerationArtifact changePlanArtifact,
                                               GenerationArtifact rollbackPointArtifact) {
        RollbackRestore restore = restore(appId, taskId, changePlanArtifact, rollbackPointArtifact);
        return GenerationArtifact.of("rollback_restore", "Orchestrator", "Rollback restore", restore.toPayload());
    }

    /** 返回恢复。 */
    RollbackRestore restore(Long appId,
                            String taskId,
                            GenerationArtifact changePlanArtifact,
                            GenerationArtifact rollbackPointArtifact) {
        ChangePlan changePlan;
        try {
            changePlan = changePlanArtifact == null ? null : ChangePlan.fromArtifact(changePlanArtifact);
        } catch (IllegalArgumentException invalidArtifact) {
            return RollbackRestore.skipped(
                    appId,
                    taskId,
                    "manual_retry_without_snapshot",
                    "",
                    "",
                    "change_plan_invalid"
            );
        }
        String rollbackStrategy = changePlan == null ? "manual_retry_without_snapshot" : changePlan.rollbackStrategy();
        if (changePlan == null || !changePlan.requiresSnapshotRollback()) {
            return RollbackRestore.skipped(appId, taskId, rollbackStrategy, "", "", "rollback_strategy_not_snapshot");
        }
        if (rollbackPointArtifact == null) {
            return RollbackRestore.skipped(
                    appId,
                    taskId,
                    rollbackStrategy,
                    "",
                    "",
                    "rollback_point_not_created"
            );
        }
        RollbackPoint rollbackPoint;
        try {
            rollbackPoint = RollbackPoint.fromArtifact(rollbackPointArtifact, appId, taskId);
        } catch (IllegalArgumentException exception) {
            log.warn("Rollback point artifact validation failed, appId: {}, taskId: {}, exceptionType: {}",
                    appId, taskId, exception.getClass().getSimpleName());
            return RollbackRestore.skipped(
                    appId,
                    taskId,
                    rollbackStrategy,
                    "",
                    "",
                    "rollback_artifact_invalid"
            );
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
        String snapshotName = rollbackPoint.snapshotName();
        String snapshotPathValue = rollbackPoint.snapshotPath();
        String projectPathValue = rollbackPoint.projectPath();
        CodeGenTypeEnum sourceType = CodeGenTypeEnum.getEnumByValue(rollbackPoint.sourceType());

        Path snapshotPath;
        Path projectPath;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
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
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
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
            generationTaskFenceGuard.assertCurrent(taskId);
            String backupPathValue = "";
            boolean projectExists = workspaceFileSystemService.isDirectory(projectPath);
            if (projectExists) {
                workspaceFileSystemService.copyDirectory(projectPath, backupPath);
                backupPathValue = backupPath.toString();
            }
            // 备份复制可能耗时，租约可在此期间被新 worker 接管；覆盖项目之前必须再次失败关闭。
            generationTaskFenceGuard.assertCurrent(taskId);
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
        } catch (GenerationExecutionPolicyException staleExecution) {
            // stale worker 属于执行所有权冲突，必须交给上层终止，不能伪装成普通恢复失败后继续收尾。
            throw staleExecution;
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

}
