package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemTestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GenerationSnapshotWorkspaceServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldResolveAndPrepareCanonicalApplicationSnapshotRoot() {
        GenerationSnapshotWorkspaceService service = service(tempDirectory.resolve("code_snapshot"));

        Path resolved = service.resolveApplicationRoot(11L);
        Path prepared = service.prepareApplicationRoot(11L);

        Path expected = tempDirectory.resolve("code_snapshot").resolve("11").toAbsolutePath().normalize();
        assertEquals(expected, resolved);
        assertEquals(expected, prepared);
        assertTrue(Files.isDirectory(prepared));
    }

    @Test
    void shouldResolveExistingSnapshotAsDirectApplicationChild() throws Exception {
        GenerationSnapshotWorkspaceService service = service(tempDirectory.resolve("code_snapshot"));
        Path snapshot = service.prepareApplicationRoot(12L).resolve("pre_generation_task-12");
        Files.createDirectory(snapshot);

        assertEquals(snapshot.toAbsolutePath().normalize(), service.resolveExistingSnapshot(
                12L,
                "pre_generation_task-12"
        ));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void shouldRejectInvalidApplicationId(Long appId) {
        GenerationSnapshotWorkspaceService service = service(tempDirectory.resolve("code_snapshot"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.resolveApplicationRoot(appId)
        );

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../another-app", "a/b", "a\\b", ".", "snapshot name"})
    void shouldRejectPathLikeSnapshotNames(String snapshotName) {
        GenerationSnapshotWorkspaceService service = service(tempDirectory.resolve("code_snapshot"));

        assertThrows(
                SnapshotNamePolicy.ValidationException.class,
                () -> service.resolveSnapshot(13L, snapshotName)
        );
        assertFalse(Files.exists(tempDirectory.resolve("code_snapshot")));
    }

    @Test
    void shouldAcceptOnlyExactArtifactReportedSnapshotPath() {
        GenerationSnapshotWorkspaceService service = service(tempDirectory.resolve("code_snapshot"));
        Path expected = service.resolveSnapshot(14L, "snapshot_14");

        assertEquals(expected, service.resolveReportedSnapshot(14L, "snapshot_14", expected.toString()));
        assertNoAuthorization(() -> service.resolveReportedSnapshot(
                14L,
                "snapshot_14",
                tempDirectory.resolve("code_snapshot").resolve("15").resolve("snapshot_14").toString()
        ));
        assertNoAuthorization(() -> service.resolveReportedSnapshot(
                14L,
                "snapshot_14",
                tempDirectory.resolve("code_snapshot").resolve("14").toString()
        ));
        assertNoAuthorization(() -> service.resolveReportedSnapshot(
                14L,
                "snapshot_14",
                tempDirectory.resolve("code_snapshot").resolve("14").resolve("snapshot_other").toString()
        ));
    }

    @Test
    void shouldRejectMissingOrMalformedReportedPath() {
        GenerationSnapshotWorkspaceService service = service(tempDirectory.resolve("code_snapshot"));

        BusinessException missing = assertThrows(
                BusinessException.class,
                () -> service.resolveReportedSnapshot(15L, "snapshot_15", " ")
        );
        BusinessException malformed = assertThrows(
                BusinessException.class,
                () -> service.resolveReportedSnapshot(15L, "snapshot_15", "invalid\0path")
        );

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), missing.getCode());
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), malformed.getCode());
    }

    @Test
    void shouldRejectSymbolicLinkSnapshotStorageRoot() throws Exception {
        Path realStorageRoot = Files.createDirectory(tempDirectory.resolve("real-snapshot-root"));
        Path linkedStorageRoot = tempDirectory.resolve("code_snapshot");
        createSymbolicLinkOrSkip(linkedStorageRoot, realStorageRoot);

        assertNoAuthorization(() -> service(linkedStorageRoot).resolveApplicationRoot(16L));
    }

    @Test
    void shouldRejectSymbolicLinkApplicationSnapshotRoot() throws Exception {
        Path storageRoot = Files.createDirectory(tempDirectory.resolve("code_snapshot"));
        Path externalApplicationRoot = Files.createDirectory(tempDirectory.resolve("external-app-root"));
        createSymbolicLinkOrSkip(storageRoot.resolve("17"), externalApplicationRoot);

        assertNoAuthorization(() -> service(storageRoot).resolveApplicationRoot(17L));
    }

    @Test
    void shouldRejectSymbolicLinkSnapshotDirectory() throws Exception {
        Path applicationRoot = Files.createDirectories(tempDirectory.resolve("code_snapshot").resolve("18"));
        Path externalSnapshot = Files.createDirectory(tempDirectory.resolve("external-snapshot"));
        createSymbolicLinkOrSkip(applicationRoot.resolve("snapshot_18"), externalSnapshot);

        assertNoAuthorization(() -> service(tempDirectory.resolve("code_snapshot"))
                .resolveSnapshot(18L, "snapshot_18"));
    }

    private GenerationSnapshotWorkspaceService service(Path snapshotRoot) {
        CodeStorageProperties properties = new CodeStorageProperties();
        properties.setOutputRootDir(tempDirectory.resolve("code_output"));
        properties.setDeployRootDir(tempDirectory.resolve("code_deploy"));
        properties.setSnapshotRootDir(snapshotRoot);
        return new GenerationSnapshotWorkspaceService(
                properties,
                WorkspaceFileSystemTestFactory.create(),
                new SnapshotNamePolicy()
        );
    }

    private void assertNoAuthorization(ThrowingOperation operation) {
        BusinessException exception = assertThrows(BusinessException.class, operation::run);
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable in this environment: " + exception.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
