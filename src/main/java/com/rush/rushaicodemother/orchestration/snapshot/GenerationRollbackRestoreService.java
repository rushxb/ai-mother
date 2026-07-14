package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceCopyResult;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.RollbackRestore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

/**
 * 失败后按 opt-in 回滚策略恢复本地快照。
 */
@Slf4j
@Component
public class GenerationRollbackRestoreService {

    private final Path codeOutputRoot;
    private final Path codeSnapshotRoot;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final SnapshotNamePolicy snapshotNamePolicy;

    @Autowired
    public GenerationRollbackRestoreService(WorkspaceFileSystemService workspaceFileSystemService,
                                            SnapshotNamePolicy snapshotNamePolicy) {
        this(
                Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR),
                Path.of(AppConstant.CODE_SNAPSHOT_ROOT_DIR),
                workspaceFileSystemService,
                snapshotNamePolicy
        );
    }

    public GenerationRollbackRestoreService(Path codeOutputRoot,
                                            Path codeSnapshotRoot,
                                            WorkspaceFileSystemService workspaceFileSystemService) {
        this(codeOutputRoot, codeSnapshotRoot, workspaceFileSystemService, new SnapshotNamePolicy());
    }

    GenerationRollbackRestoreService(Path codeOutputRoot,
                                     Path codeSnapshotRoot,
                                     WorkspaceFileSystemService workspaceFileSystemService,
                                     SnapshotNamePolicy snapshotNamePolicy) {
        this.codeOutputRoot = codeOutputRoot.toAbsolutePath().normalize();
        this.codeSnapshotRoot = codeSnapshotRoot.toAbsolutePath().normalize();
        this.workspaceFileSystemService = workspaceFileSystemService;
        this.snapshotNamePolicy = snapshotNamePolicy;
    }

    public GenerationArtifact restoreIfAllowed(Long appId,
                                               String taskId,
                                               GenerationArtifact changePlanArtifact,
                                               GenerationArtifact rollbackPointArtifact) {
        RollbackRestore restore = restore(appId, taskId, changePlanArtifact, rollbackPointArtifact);
        return GenerationArtifact.of("rollback_restore", "Orchestrator", "失败后本地回滚结果", restore.toPayload());
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
        if (snapshotPathValue.isBlank() || projectPathValue.isBlank()) {
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
        if (!matchesArtifactContext(appId, taskId, rollbackPayload, sourceTypeValue)) {
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
            snapshotPath = Path.of(snapshotPathValue).toAbsolutePath().normalize();
            projectPath = Path.of(projectPathValue).toAbsolutePath().normalize();
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
        if (!isAllowedSnapshotPath(appId, snapshotPath) || !isExpectedProjectPath(appId, sourceTypeValue, projectPath)) {
            return RollbackRestore.skipped(
                    appId,
                    taskId,
                    rollbackStrategy,
                    snapshotPathValue,
                    projectPathValue,
                    "rollback_path_out_of_root"
            );
        }
        Path backupPath = backupPath(appId, taskId);
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
        } catch (Exception e) {
            log.warn("失败后本地快照恢复失败，appId: {}, taskId: {}, exceptionType: {}",
                    appId, taskId, e.getClass().getSimpleName());
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
        String appDir = appId == null ? "unknown" : String.valueOf(appId);
        String backupName = snapshotNamePolicy.createTaskScopedName("failed_generation", taskId);
        return codeSnapshotRoot.resolve(appDir)
                .resolve(backupName)
                .toAbsolutePath()
                .normalize();
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
                                           String sourceTypeValue) {
        return appId != null
                && appId > 0
                && taskId != null
                && !taskId.isBlank()
                && String.valueOf(appId).equals(stringValue(rollbackPayload.get("appId")))
                && stringValue(taskId).equals(stringValue(rollbackPayload.get("taskId")))
                && CodeGenTypeEnum.getEnumByValue(sourceTypeValue) != null;
    }

    private boolean isAllowedSnapshotPath(Long appId, Path snapshotPath) {
        Path applicationSnapshotRoot = codeSnapshotRoot.resolve(String.valueOf(appId)).normalize();
        return snapshotPath.startsWith(applicationSnapshotRoot)
                && !snapshotPath.equals(applicationSnapshotRoot)
                && applicationSnapshotRoot.equals(snapshotPath.getParent());
    }

    private boolean isExpectedProjectPath(Long appId, String sourceTypeValue, Path projectPath) {
        Path expectedProjectPath = codeOutputRoot.resolve(sourceTypeValue + "_" + appId).normalize();
        return projectPath.equals(expectedProjectPath)
                && !projectPath.equals(codeOutputRoot)
                && projectPath.startsWith(codeOutputRoot);
    }
}
