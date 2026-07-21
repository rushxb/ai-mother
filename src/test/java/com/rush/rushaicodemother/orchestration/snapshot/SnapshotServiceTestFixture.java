package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.config.GenerationCommitProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemTestFactory;
import com.rush.rushaicodemother.infrastructure.git.GitCommandExecutor;
import com.rush.rushaicodemother.infrastructure.git.GitTransactionResourceManager;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;

import java.nio.file.Path;

import static org.mockito.Mockito.mock;

/** Production-constructor fixture for snapshot orchestration unit tests. */
final class SnapshotServiceTestFixture {

    private SnapshotServiceTestFixture() {
    }

    static GenerationRollbackPointService rollbackPointService(Path outputRoot, Path snapshotRoot) {
        return rollbackPointService(
                outputRoot, snapshotRoot, mock(GenerationTaskFenceGuard.class));
    }

    static GenerationRollbackPointService rollbackPointService(
            Path outputRoot,
            Path snapshotRoot,
            GenerationTaskFenceGuard fenceGuard) {
        Components components = components(outputRoot, snapshotRoot);
        return new GenerationRollbackPointService(
                components.generationWorkspaceService(),
                components.snapshotWorkspaceService(),
                components.workspaceFileSystemService(),
                components.snapshotNamePolicy(),
                fenceGuard
        );
    }

    static GenerationDiffSummaryService diffSummaryService(Path outputRoot, Path snapshotRoot) {
        Components components = components(outputRoot, snapshotRoot);
        return new GenerationDiffSummaryService(
                components.generationWorkspaceService(),
                components.snapshotWorkspaceService(),
                components.workspaceFileSystemService()
        );
    }

    static GenerationRollbackRestoreService rollbackRestoreService(Path outputRoot, Path snapshotRoot) {
        return rollbackRestoreService(
                outputRoot, snapshotRoot, mock(GenerationTaskFenceGuard.class));
    }

    static GenerationRollbackRestoreService rollbackRestoreService(
            Path outputRoot,
            Path snapshotRoot,
            GenerationTaskFenceGuard fenceGuard) {
        Components components = components(outputRoot, snapshotRoot);
        return new GenerationRollbackRestoreService(
                components.generationWorkspaceService(),
                components.snapshotWorkspaceService(),
                components.workspaceFileSystemService(),
                components.snapshotNamePolicy(),
                fenceGuard
        );
    }

    static GenerationCommitService commitService(
            GenerationOrchestrationMetricsCollector metricsCollector,
            GitCommandExecutor gitCommandExecutor,
            Path outputRoot
    ) {
        return commitService(metricsCollector, gitCommandExecutor, outputRoot, new GenerationCommitProperties());
    }

    static GenerationCommitService commitService(
            GenerationOrchestrationMetricsCollector metricsCollector,
            GitCommandExecutor gitCommandExecutor,
            Path outputRoot,
            GenerationCommitProperties properties
    ) {
        Components components = components(outputRoot, siblingRoot(outputRoot, "code_snapshot"));
        return new GenerationCommitService(
                metricsCollector,
                gitCommandExecutor,
                components.workspaceFileSystemService(),
                new GitTransactionResourceManager(),
                components.generationWorkspaceService(),
                properties
        );
    }

    static Components components(Path outputRoot, Path snapshotRoot) {
        CodeStorageProperties storageProperties = new CodeStorageProperties();
        storageProperties.setOutputRootDir(outputRoot);
        storageProperties.setDeployRootDir(siblingRoot(outputRoot, "code_deploy"));
        storageProperties.setSnapshotRootDir(snapshotRoot);
        WorkspaceFileSystemService fileSystemService = WorkspaceFileSystemTestFactory.create();
        SnapshotNamePolicy snapshotNamePolicy = new SnapshotNamePolicy();
        GenerationWorkspaceService generationWorkspaceService = new GenerationWorkspaceService(storageProperties);
        GenerationSnapshotWorkspaceService snapshotWorkspaceService = new GenerationSnapshotWorkspaceService(
                storageProperties,
                fileSystemService,
                snapshotNamePolicy
        );
        return new Components(
                storageProperties,
                fileSystemService,
                snapshotNamePolicy,
                generationWorkspaceService,
                snapshotWorkspaceService
        );
    }

    private static Path siblingRoot(Path outputRoot, String name) {
        Path absoluteOutputRoot = outputRoot.toAbsolutePath().normalize();
        Path parent = absoluteOutputRoot.getParent();
        return (parent == null ? absoluteOutputRoot.resolveSibling(name) : parent.resolve(name)).normalize();
    }

    record Components(
            CodeStorageProperties storageProperties,
            WorkspaceFileSystemService workspaceFileSystemService,
            SnapshotNamePolicy snapshotNamePolicy,
            GenerationWorkspaceService generationWorkspaceService,
            GenerationSnapshotWorkspaceService snapshotWorkspaceService
    ) {
    }
}
