package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemException;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemTestFactory;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GenerationSnapshotWorkspaceServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldPublishSelfContainedBundleUnderImmutableSnapshotId() throws Exception {
        GenerationSnapshotWorkspaceService service = service(tempDirectory.resolve("code_snapshot"));
        Path source = Files.createDirectories(tempDirectory.resolve("workspace/source"));
        Files.writeString(source.resolve("index.html"), "v1");

        StoredSnapshot snapshot = service.capture(capture("release", source, 1L), () -> {
        });

        assertEquals(UUID.fromString(snapshot.snapshotId()).toString(), snapshot.snapshotId());
        assertEquals(snapshot.snapshotId(), snapshot.containerPath().getFileName().toString());
        assertNotEquals(snapshot.snapshotName(), snapshot.containerPath().getFileName().toString());
        assertTrue(Files.isRegularFile(snapshot.containerPath().resolve("manifest.json")));
        String manifest = Files.readString(snapshot.containerPath().resolve("manifest.json"));
        for (String requiredField : java.util.List.of(
                "snapshotId", "appId", "kind", "codeGenType", "scope", "taskId",
                "executionEpoch", "copyPolicy", "treeHash", "fileCount", "byteCount", "createdAt")) {
            assertTrue(manifest.contains("\"" + requiredField + "\""), requiredField);
        }
        assertEquals("v1", Files.readString(snapshot.payloadPath().resolve("index.html")));
        assertEquals(snapshot, service.requireSnapshot(SnapshotSelector.exact(snapshot)));
        assertEquals(1, service.listSnapshots(1L).size());
        try (var children = Files.list(service.resolveApplicationRoot(1L))) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith(".snapshot-staging-")));
        }
    }

    @Test
    void snapshotFromFrontendScopeMustNotRestoreIntoBackendScope() throws Exception {
        GenerationSnapshotWorkspaceService service = service(tempDirectory.resolve("code_snapshot"));
        Path frontend = Files.createDirectories(tempDirectory.resolve("workspace/frontend"));
        Path backend = Files.createDirectories(tempDirectory.resolve("workspace/backend"));
        Files.writeString(frontend.resolve("App.vue"), "frontend-snapshot");
        Files.writeString(backend.resolve("Application.java"), "backend-current");
        StoredSnapshot snapshot = service.capture(new SnapshotCapture(
                "safe",
                new SnapshotScope(19L, CodeGenTypeEnum.FULL_STACK_PROJECT, "frontend"),
                frontend,
                SnapshotKind.MANUAL,
                "task-19",
                3
        ), () -> {
        });
        SnapshotSelector wrongScope = new SnapshotSelector(
                snapshot.snapshotName(),
                new SnapshotScope(19L, CodeGenTypeEnum.FULL_STACK_PROJECT, "backend"),
                snapshot.snapshotId(),
                snapshot.kind(),
                snapshot.creatorTaskId(),
                snapshot.creatorExecutionEpoch(),
                snapshot.manifestSha256()
        );

        SnapshotStoreException exception = assertThrows(
                SnapshotStoreException.class,
                () -> service.restore(wrongScope, backend, () -> {
                })
        );

        assertEquals(SnapshotStoreException.Reason.PROVENANCE_MISMATCH, exception.reason());
        assertEquals("backend-current", Files.readString(backend.resolve("Application.java")));
        assertFalse(Files.exists(backend.resolve("App.vue")));
    }

    @Test
    void tamperedPayloadMustFailBeforeTargetDirectoryMoves() throws Exception {
        GenerationSnapshotWorkspaceService service = service(tempDirectory.resolve("code_snapshot"));
        Path source = Files.createDirectories(tempDirectory.resolve("workspace/source"));
        Path target = Files.createDirectories(tempDirectory.resolve("workspace/target"));
        Files.writeString(source.resolve("app.txt"), "snapshot");
        Files.writeString(target.resolve("app.txt"), "current");
        StoredSnapshot snapshot = service.capture(capture("tamper", source, 2L), () -> {
        });
        Files.writeString(snapshot.payloadPath().resolve("app.txt"), "tampered");

        SnapshotStoreException exception = assertThrows(
                SnapshotStoreException.class,
                () -> service.restore(SnapshotSelector.exact(snapshot), target, () -> {
                })
        );

        assertEquals(SnapshotStoreException.Reason.CONTENT_MISMATCH, exception.reason());
        assertEquals("current", Files.readString(target.resolve("app.txt")));
    }

    @Test
    void crossApplicationTypeTaskAndEpochSelectorsMustNeverMoveTarget() throws Exception {
        GenerationSnapshotWorkspaceService service = service(tempDirectory.resolve("code_snapshot"));
        Path source = Files.createDirectories(tempDirectory.resolve("workspace/source"));
        Path target = Files.createDirectories(tempDirectory.resolve("workspace/target"));
        Files.writeString(source.resolve("app.txt"), "snapshot");
        Files.writeString(target.resolve("app.txt"), "current");
        StoredSnapshot snapshot = service.capture(new SnapshotCapture(
                "identity",
                new SnapshotScope(30L, CodeGenTypeEnum.VUE_PROJECT, "."),
                source,
                SnapshotKind.MANUAL,
                "task-30",
                2L
        ), () -> {
        });
        java.util.List<SnapshotSelector> mismatches = java.util.List.of(
                new SnapshotSelector(
                        snapshot.snapshotName(),
                        new SnapshotScope(31L, CodeGenTypeEnum.VUE_PROJECT, "."),
                        snapshot.snapshotId(), snapshot.kind(), snapshot.creatorTaskId(),
                        snapshot.creatorExecutionEpoch(), snapshot.manifestSha256()),
                new SnapshotSelector(
                        snapshot.snapshotName(),
                        new SnapshotScope(30L, CodeGenTypeEnum.HTML, "."),
                        snapshot.snapshotId(), snapshot.kind(), snapshot.creatorTaskId(),
                        snapshot.creatorExecutionEpoch(), snapshot.manifestSha256()),
                new SnapshotSelector(
                        snapshot.snapshotName(), snapshot.scope(), snapshot.snapshotId(), snapshot.kind(),
                        "task-other", snapshot.creatorExecutionEpoch(), snapshot.manifestSha256()),
                new SnapshotSelector(
                        snapshot.snapshotName(), snapshot.scope(), snapshot.snapshotId(), snapshot.kind(),
                        snapshot.creatorTaskId(), 3L, snapshot.manifestSha256())
        );

        for (SnapshotSelector mismatch : mismatches) {
            assertThrows(SnapshotStoreException.class, () -> service.restore(mismatch, target, () -> {
            }));
            assertEquals("current", Files.readString(target.resolve("app.txt")));
        }
    }

    @Test
    void malformedManifestMustFailClosed() throws Exception {
        GenerationSnapshotWorkspaceService service = service(tempDirectory.resolve("code_snapshot"));
        Path source = Files.createDirectories(tempDirectory.resolve("workspace/source"));
        Files.writeString(source.resolve("app.txt"), "snapshot");
        StoredSnapshot snapshot = service.capture(capture("manifest", source, 3L), () -> {
        });
        Files.writeString(snapshot.containerPath().resolve("manifest.json"), "{broken");

        SnapshotStoreException exception = assertThrows(
                SnapshotStoreException.class,
                () -> service.requireSnapshot(SnapshotSelector.exact(snapshot))
        );

        assertEquals(SnapshotStoreException.Reason.MANIFEST_INVALID, exception.reason());
    }

    @Test
    void legacyNameBasedDirectoryMustNotEnterSnapshotCatalog() throws Exception {
        GenerationSnapshotWorkspaceService service = service(tempDirectory.resolve("code_snapshot"));
        Files.createDirectory(service.prepareApplicationRoot(4L).resolve("legacy_snapshot"));

        SnapshotStoreException exception = assertThrows(
                SnapshotStoreException.class,
                () -> service.listSnapshots(4L)
        );

        assertEquals(SnapshotStoreException.Reason.UNSUPPORTED_SCHEMA, exception.reason());
    }

    @Test
    void captureOrReuseMustRequireExactCreationProvenance() throws Exception {
        GenerationSnapshotWorkspaceService service = service(tempDirectory.resolve("code_snapshot"));
        Path source = Files.createDirectories(tempDirectory.resolve("workspace/source"));
        Files.writeString(source.resolve("app.txt"), "snapshot");
        SnapshotCapture capture = capture("stable", source, 5L);

        StoredSnapshot first = service.captureOrReuse(capture, () -> {
        });
        StoredSnapshot second = service.captureOrReuse(capture, () -> {
        });

        assertEquals(first.snapshotId(), second.snapshotId());
        SnapshotStoreException conflict = assertThrows(
                SnapshotStoreException.class,
                () -> service.captureOrReuse(new SnapshotCapture(
                        "stable",
                        capture.scope(),
                        source,
                        capture.kind(),
                        capture.creatorTaskId(),
                        capture.creatorExecutionEpoch() + 1
                ), () -> {
                })
        );
        assertEquals(SnapshotStoreException.Reason.ALREADY_EXISTS, conflict.reason());
    }

    @Test
    void snapshotCaptureMustRejectNestedSymbolicLinkWithoutPublishingContainer() throws Exception {
        GenerationSnapshotWorkspaceService service = service(tempDirectory.resolve("code_snapshot"));
        Path source = Files.createDirectories(tempDirectory.resolve("workspace/source"));
        Path externalFile = tempDirectory.resolve("external-secret.txt");
        Files.writeString(externalFile, "must-not-be-silently-dropped");
        createSymbolicLinkOrSkip(source.resolve("linked-secret.txt"), externalFile);

        WorkspaceFileSystemException exception = assertThrows(
                WorkspaceFileSystemException.class,
                () -> service.capture(capture("strict", source, 6L), () -> {
                })
        );

        assertEquals(WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK, exception.reason());
        assertTrue(service.listSnapshots(6L).isEmpty());
    }

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

    private SnapshotCapture capture(String name, Path source, long appId) {
        return new SnapshotCapture(
                name,
                new SnapshotScope(appId, CodeGenTypeEnum.VUE_PROJECT, "."),
                source,
                SnapshotKind.MANUAL,
                "task-" + appId,
                1
        );
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
            assumeTrue(false, "Symbolic links are unavailable in this environment: "
                    + exception.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
