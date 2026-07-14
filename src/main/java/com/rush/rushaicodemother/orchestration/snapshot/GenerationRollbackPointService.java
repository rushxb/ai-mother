package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceCopyResult;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.RollbackPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 在进入真实代码生成前创建本地回滚点。
 */
@Slf4j
@Component
public class GenerationRollbackPointService {

    private final Path codeOutputRoot;
    private final Path codeSnapshotRoot;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final SnapshotNamePolicy snapshotNamePolicy;

    @Autowired
    public GenerationRollbackPointService(WorkspaceFileSystemService workspaceFileSystemService,
                                          SnapshotNamePolicy snapshotNamePolicy) {
        this(
                Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR),
                Path.of(AppConstant.CODE_SNAPSHOT_ROOT_DIR),
                workspaceFileSystemService,
                snapshotNamePolicy
        );
    }

    public GenerationRollbackPointService(Path codeOutputRoot,
                                          Path codeSnapshotRoot,
                                          WorkspaceFileSystemService workspaceFileSystemService) {
        this(codeOutputRoot, codeSnapshotRoot, workspaceFileSystemService, new SnapshotNamePolicy());
    }

    GenerationRollbackPointService(Path codeOutputRoot,
                                   Path codeSnapshotRoot,
                                   WorkspaceFileSystemService workspaceFileSystemService,
                                   SnapshotNamePolicy snapshotNamePolicy) {
        this.codeOutputRoot = codeOutputRoot.toAbsolutePath().normalize();
        this.codeSnapshotRoot = codeSnapshotRoot.toAbsolutePath().normalize();
        this.workspaceFileSystemService = workspaceFileSystemService;
        this.snapshotNamePolicy = snapshotNamePolicy;
    }

    public GenerationArtifact prepareRollbackPoint(GenerationOrchestrationRequest request,
                                                   CodeGenTypeEnum targetType,
                                                   String taskId) {
        RollbackPoint rollbackPoint = createRollbackPoint(request, targetType, taskId);
        return GenerationArtifact.of("rollback_point", "Orchestrator", "生成前回滚点", rollbackPoint.toPayload());
    }

    RollbackPoint createRollbackPoint(GenerationOrchestrationRequest request,
                                      CodeGenTypeEnum targetType,
                                      String taskId) {
        App app = request == null ? null : request.app();
        Long appId = app == null ? null : app.getId();
        CodeGenTypeEnum sourceType = resolveSourceType(request, app);
        String sourceTypeValue = sourceType == null ? "" : sourceType.getValue();
        String targetTypeValue = targetType == null ? "" : targetType.getValue();
        if (appId == null || appId <= 0) {
            return RollbackPoint.skipped(appId, taskId, "", sourceTypeValue, targetTypeValue, "invalid_app_id");
        }
        if (request == null || !request.hasGeneratedCode()) {
            return RollbackPoint.skipped(appId, taskId, "", sourceTypeValue, targetTypeValue, "no_existing_generated_code");
        }
        if (sourceType == null) {
            return RollbackPoint.skipped(appId, taskId, "", "", targetTypeValue, "unknown_source_type");
        }
        Path projectPath = resolveProjectPath(appId, sourceType);
        try {
            if (!workspaceFileSystemService.isDirectory(projectPath)) {
                return RollbackPoint.skipped(
                        appId,
                        taskId,
                        projectPath.toString(),
                        sourceTypeValue,
                        targetTypeValue,
                        "project_directory_missing"
                );
            }
            Path snapshotRoot = codeSnapshotRoot.resolve(String.valueOf(appId))
                    .toAbsolutePath()
                    .normalize();
            workspaceFileSystemService.ensureDirectory(snapshotRoot);
            String snapshotName = buildSnapshotName(taskId);
            Path snapshotPath = snapshotRoot.resolve(snapshotName).normalize();
            WorkspaceCopyResult copyResult = workspaceFileSystemService.copyDirectory(projectPath, snapshotPath);
            return RollbackPoint.created(
                    appId,
                    taskId,
                    snapshotName,
                    snapshotPath.toString(),
                    projectPath.toString(),
                    sourceTypeValue,
                    targetTypeValue,
                    copyResult.fileCount()
            );
        } catch (Exception e) {
            log.warn("创建生成前回滚点失败，appId: {}, taskId: {}", appId, taskId, LogExceptionSanitizer.sanitize(e));
            return RollbackPoint.skipped(
                    appId,
                    taskId,
                    projectPath.toString(),
                    sourceTypeValue,
                    targetTypeValue,
                    "snapshot_create_failed"
            );
        }
    }

    private CodeGenTypeEnum resolveSourceType(GenerationOrchestrationRequest request, App app) {
        if (request != null && request.currentType() != null) {
            return request.currentType();
        }
        return app == null ? null : CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
    }

    private Path resolveProjectPath(Long appId, CodeGenTypeEnum sourceType) {
        String projectDirName = sourceType.getValue() + "_" + appId;
        return codeOutputRoot.resolve(projectDirName)
                .toAbsolutePath()
                .normalize();
    }

    private String buildSnapshotName(String taskId) {
        return snapshotNamePolicy.createTaskScopedName("pre_generation", taskId);
    }

}
