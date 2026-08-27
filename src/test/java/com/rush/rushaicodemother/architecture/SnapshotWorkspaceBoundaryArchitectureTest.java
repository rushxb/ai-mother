package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents snapshot, rollback and commit consumers from rebuilding trusted filesystem paths. */
class SnapshotWorkspaceBoundaryArchitectureTest {

    private static final Path SNAPSHOT_WORKSPACE_SERVICE = sourcePath(
            "orchestration", "snapshot", "GenerationSnapshotWorkspaceService.java"
    );
    private static final List<Path> SNAPSHOT_CONSUMERS = List.of(
            sourcePath("orchestration", "snapshot", "GenerationRollbackPointService.java"),
            sourcePath("orchestration", "snapshot", "GenerationDiffSummaryService.java"),
            sourcePath("orchestration", "snapshot", "GenerationRollbackRestoreService.java"),
            sourcePath("ai", "tools", "DiffSummaryTool.java"),
            sourcePath("ai", "tools", "SnapshotRollbackTool.java")
    );
    private static final Path COMMIT_SERVICE = sourcePath(
            "orchestration", "snapshot", "GenerationCommitService.java"
    );
    private static final List<String> FORBIDDEN_PATH_RECONSTRUCTION = List.of(
            "AppConstant",
            "CODE_OUTPUT_ROOT_DIR",
            "CODE_SNAPSHOT_ROOT_DIR",
            "codeOutputRoot",
            "codeSnapshotRoot",
            "Path.of(AppConstant",
            "getValue() + \"_\"",
            "snapshotRoot.resolve("
    );

    @Test
    void snapshotBoundaryMustOwnConfiguredSnapshotRootAndFilesystemValidation() throws Exception {
        String source = Files.readString(SNAPSHOT_WORKSPACE_SERVICE);

        assertTrue(source.contains("CodeStorageProperties"));
        assertTrue(source.contains("storageProperties.snapshotRoot()"));
        assertTrue(source.contains("WorkspaceFileSystemService"));
        assertTrue(source.contains("requireSnapshot("));
        assertTrue(source.contains("fingerprintDirectory("));
        assertFalse(source.contains("AppConstant"));
        assertFalse(source.contains("CODE_SNAPSHOT_ROOT_DIR"));
    }

    @Test
    void snapshotConsumersMustUseCanonicalSnapshotBoundary() throws Exception {
        for (Path sourcePath : SNAPSHOT_CONSUMERS) {
            String source = Files.readString(sourcePath);
            String moduleName = sourcePath.getFileName().toString();

            assertTrue(
                    source.contains("GenerationSnapshotWorkspaceService"),
                    () -> moduleName + " must depend on the canonical snapshot boundary"
            );
            assertNoPathReconstruction(source, moduleName);
        }
    }

    @Test
    void snapshotConsumersMustDelegateSnapshotTransactionsToCanonicalBoundary() throws Exception {
        String rollbackPointSource = Files.readString(SNAPSHOT_CONSUMERS.get(0));
        String rollbackRestoreSource = Files.readString(SNAPSHOT_CONSUMERS.get(2));

        assertTrue(rollbackPointSource.contains("snapshotWorkspaceService.captureOrReuse("));
        assertTrue(rollbackRestoreSource.contains("snapshotWorkspaceService.restore("));
        assertFalse(rollbackPointSource.contains("workspaceFileSystemService.copyDirectory("));
        assertFalse(rollbackRestoreSource.contains("workspaceFileSystemService.replaceDirectory("));
        assertFalse(rollbackPointSource.contains("Files.move("));
        assertFalse(rollbackRestoreSource.contains("Files.move("));
    }

    @Test
    void commitServiceMustResolveArtifactPathThroughCanonicalWorkspaceBoundary() throws Exception {
        String source = Files.readString(COMMIT_SERVICE);

        assertTrue(source.contains("GenerationWorkspaceService"));
        assertTrue(source.contains("generationWorkspaceService.resolveReportedWorkspace("));
        assertNoPathReconstruction(source, "GenerationCommitService");
    }

    private void assertNoPathReconstruction(String source, String moduleName) {
        for (String forbidden : FORBIDDEN_PATH_RECONSTRUCTION) {
            assertFalse(
                    source.contains(forbidden),
                    () -> moduleName + " rebuilds a trusted workspace path: " + forbidden
            );
        }
    }

    private static Path sourcePath(String... childSegments) {
        Path sourceRoot = Path.of("src", "main", "java", "com", "rush", "rushaicodemother");
        for (String segment : childSegments) {
            sourceRoot = sourceRoot.resolve(segment);
        }
        return sourceRoot;
    }
}
