package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceCopyResult;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.RollbackPoint;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Creates bounded rollback snapshots for generated application workspaces.
 */
@Slf4j
@Component
public class GenerationRollbackPointService {

    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationSnapshotWorkspaceService snapshotWorkspaceService;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final SnapshotNamePolicy snapshotNamePolicy;

    public GenerationRollbackPointService(
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

    public GenerationArtifact prepareRollbackPoint(GenerationOrchestrationRequest request,
                                                   CodeGenTypeEnum targetType,
                                                   String taskId) {
        RollbackPoint rollbackPoint = createRollbackPoint(request, targetType, taskId);
        return GenerationArtifact.of("rollback_point", "Orchestrator", "Rollback point", rollbackPoint.toPayload());
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

        Path projectPath = null;
        try {
            GenerationWorkspace workspace = generationWorkspaceService.resolve(appId, sourceType);
            projectPath = workspace.canonicalRootPath();
            if (!workspace.exists() || !workspaceFileSystemService.isDirectory(projectPath)) {
                return RollbackPoint.skipped(
                        appId,
                        taskId,
                        projectPath.toString(),
                        sourceTypeValue,
                        targetTypeValue,
                        "project_directory_missing"
                );
            }
            snapshotWorkspaceService.prepareApplicationRoot(appId);
            String snapshotName = buildSnapshotName(taskId);
            Path snapshotPath = snapshotWorkspaceService.resolveSnapshot(appId, snapshotName);
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
        } catch (Exception exception) {
            log.warn("Failed to create rollback point, appId: {}, taskId: {}",
                    appId, taskId, LogExceptionSanitizer.sanitize(exception));
            return RollbackPoint.skipped(
                    appId,
                    taskId,
                    projectPath == null ? "" : projectPath.toString(),
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

    private String buildSnapshotName(String taskId) {
        return snapshotNamePolicy.createTaskScopedName("pre_generation", taskId);
    }
}
