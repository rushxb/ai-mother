package com.rush.rushaicodemother.infrastructure.filesystem;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceFileSystemServiceTest {

    @Test
    void shouldScanAndReadOnlySafeProjectFiles() throws Exception {
        Path root = createTempWorkspace("scan");
        try {
            write(root, "src/App.vue", "<template>safe</template>");
            write(root, "src/api.ts", "export const api = true");
            write(root, "node_modules/pkg/index.js", "ignored");
            write(root, ".env", "TOKEN=secret");
            WorkspaceFileSystemService service = serviceWithDefaults();

            WorkspaceFileSystemService.WorkspaceScan scan = service.scanProject(root);

            assertEquals(List.of("src/App.vue", "src/api.ts"), scan.files().stream()
                    .map(WorkspaceFileSystemService.WorkspaceFileMetadata::relativePath)
                    .toList());
            assertEquals("<template>safe</template>", service.readUtf8(scan, scan.files().getFirst(), 1024));
        } finally {
            cleanup(root);
        }
    }

    @Test
    void scanMustStopWhenContinuationCheckRejectsWork() throws Exception {
        Path root = createTempWorkspace("scan-cancelled");
        try {
            write(root, "src/one.ts", "1");
            write(root, "src/two.ts", "2");
            WorkspaceFileSystemService service = serviceWithDefaults();
            AtomicInteger checks = new AtomicInteger();

            assertThrows(
                    IllegalStateException.class,
                    () -> service.scanProject(root, () -> {
                        if (checks.incrementAndGet() >= 3) {
                            throw new IllegalStateException("扫描已取消");
                        }
                    })
            );

            assertEquals(3, checks.get());
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldFailExplicitlyWhenScanFileLimitIsExceeded() throws Exception {
        Path root = createTempWorkspace("limit");
        try {
            write(root, "src/one.ts", "1");
            write(root, "src/two.ts", "2");
            WorkspaceFileSystemProperties properties = new WorkspaceFileSystemProperties();
            properties.setMaxFiles(1);
            WorkspaceFileSystemService service = new WorkspaceFileSystemService(properties);

            WorkspaceFileSystemException exception = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.scanProject(root)
            );

            assertEquals(WorkspaceFileSystemException.Reason.FILE_LIMIT_EXCEEDED, exception.reason());
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldRejectFileChangedAfterScan() throws Exception {
        Path root = createTempWorkspace("changed");
        try {
            write(root, "src/App.vue", "before");
            WorkspaceFileSystemService service = serviceWithDefaults();
            WorkspaceFileSystemService.WorkspaceScan scan = service.scanProject(root);
            write(root, "src/App.vue", "after-content");

            WorkspaceFileSystemException exception = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.readUtf8(scan, scan.files().getFirst(), 1024)
            );

            assertEquals(WorkspaceFileSystemException.Reason.FILE_CHANGED, exception.reason());
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldCreateCompleteSnapshotAndReplaceWorkspace() throws Exception {
        Path root = createTempWorkspace("copy");
        Path source = root.resolve("source");
        Path snapshots = root.resolve("snapshots");
        Path project = root.resolve("project");
        try {
            write(source, "src/App.vue", "snapshot-version");
            write(source, "node_modules/pkg/index.js", "ignored");
            write(project, "src/App.vue", "current-version");
            write(project, "src/OnlyCurrent.vue", "remove-me");
            Files.createDirectories(snapshots);
            WorkspaceFileSystemService service = serviceWithDefaults();

            WorkspaceFileSystemService.WorkspaceCopyResult copy = service.copyDirectory(
                    source,
                    snapshots.resolve("snapshot_one")
            );
            WorkspaceFileSystemService.WorkspaceCopyResult restore = service.replaceDirectory(
                    copy.targetDirectory(),
                    project
            );

            assertEquals(1, copy.fileCount());
            assertEquals(1, restore.fileCount());
            assertEquals("snapshot-version", Files.readString(project.resolve("src/App.vue")));
            assertFalse(Files.exists(project.resolve("src/OnlyCurrent.vue")));
            assertFalse(Files.exists(copy.targetDirectory().resolve("node_modules")));
            assertTrue(service.isDirectory(copy.targetDirectory()));
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldRetryTransientAccessDenialBeforePublishingSnapshot() throws Exception {
        Path root = createTempWorkspace("publish-retry");
        Path source = root.resolve("source");
        Path target = root.resolve("snapshots/snapshot");
        try {
            write(source, "src/App.vue", "snapshot");
            WorkspaceFileSystemProperties properties = retryProperties(3, 0);
            AtomicInteger attempts = new AtomicInteger();
            WorkspaceFileSystemService service = new WorkspaceFileSystemService(properties,
                    (moveSource, moveTarget, options) -> {
                        if (attempts.incrementAndGet() < 3) {
                            throw accessDenied(moveSource, moveTarget);
                        }
                        return Files.move(moveSource, moveTarget, options);
                    });

            service.copyDirectory(source, target);

            assertEquals(3, attempts.get());
            assertEquals("snapshot", Files.readString(target.resolve("src/App.vue")));
            assertNoTemporarySibling(target, "copy");
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldStopPublishingWhenTargetAppearsDuringRetry() throws Exception {
        Path root = createTempWorkspace("publish-race");
        Path source = root.resolve("source");
        Path target = root.resolve("snapshots/snapshot");
        try {
            write(source, "src/App.vue", "snapshot");
            AtomicInteger attempts = new AtomicInteger();
            WorkspaceFileSystemService service = new WorkspaceFileSystemService(retryProperties(5, 0),
                    (moveSource, moveTarget, options) -> {
                        attempts.incrementAndGet();
                        Files.createDirectory(moveTarget);
                        throw accessDenied(moveSource, moveTarget);
                    });

            WorkspaceFileSystemException exception = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.copyDirectory(source, target)
            );

            assertEquals(WorkspaceFileSystemException.Reason.COPY_FAILED, exception.reason());
            assertTrue(exception.getCause() instanceof AccessDeniedException);
            assertEquals(1, attempts.get());
            assertTrue(Files.isDirectory(target));
            assertNoTemporarySibling(target, "copy");
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldExposePermanentAccessDenialAfterConfiguredAttempts() throws Exception {
        Path root = createTempWorkspace("publish-denied");
        Path source = root.resolve("source");
        Path target = root.resolve("snapshots/snapshot");
        try {
            write(source, "src/App.vue", "snapshot");
            AtomicInteger attempts = new AtomicInteger();
            WorkspaceFileSystemService service = new WorkspaceFileSystemService(retryProperties(2, 0),
                    (moveSource, moveTarget, options) -> {
                        attempts.incrementAndGet();
                        throw accessDenied(moveSource, moveTarget);
                    });

            WorkspaceFileSystemException exception = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.copyDirectory(source, target)
            );

            assertEquals(WorkspaceFileSystemException.Reason.COPY_FAILED, exception.reason());
            assertTrue(exception.getCause() instanceof AccessDeniedException);
            assertEquals(2, attempts.get());
            assertFalse(Files.exists(target));
            assertNoTemporarySibling(target, "copy");
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldFallBackWhenAtomicDirectoryMoveIsUnsupported() throws Exception {
        Path root = createTempWorkspace("publish-fallback");
        Path source = root.resolve("source");
        Path target = root.resolve("snapshots/snapshot");
        try {
            write(source, "src/App.vue", "snapshot");
            AtomicInteger attempts = new AtomicInteger();
            WorkspaceFileSystemService service = new WorkspaceFileSystemService(retryProperties(1, 0),
                    (moveSource, moveTarget, options) -> {
                        attempts.incrementAndGet();
                        if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                            throw new AtomicMoveNotSupportedException(
                                    moveSource.toString(),
                                    moveTarget.toString(),
                                    "atomic move unavailable"
                            );
                        }
                        return Files.move(moveSource, moveTarget, options);
                    });

            service.copyDirectory(source, target);

            assertEquals(2, attempts.get());
            assertTrue(Files.isDirectory(target));
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldPreserveInterruptStatusWhileWaitingToPublish() throws Exception {
        Path root = createTempWorkspace("publish-interrupt");
        Path source = root.resolve("source");
        Path target = root.resolve("snapshots/snapshot");
        try {
            write(source, "src/App.vue", "snapshot");
            WorkspaceFileSystemService service = new WorkspaceFileSystemService(retryProperties(2, 50),
                    (moveSource, moveTarget, options) -> {
                        Thread.currentThread().interrupt();
                        throw accessDenied(moveSource, moveTarget);
                    });

            WorkspaceFileSystemException exception = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.copyDirectory(source, target)
            );

            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(exception.getCause() instanceof InterruptedIOException);
            assertTrue(exception.getCause().getCause() instanceof AccessDeniedException);
            assertNoTemporarySibling(target, "copy");
        } finally {
            Thread.interrupted();
            cleanup(root);
        }
    }

    @Test
    void shouldRestoreOriginalWorkspaceWhenStagedActivationFails() throws Exception {
        Path root = createTempWorkspace("restore-failure");
        Path source = root.resolve("snapshot");
        Path target = root.resolve("project");
        try {
            write(source, "src/App.vue", "snapshot-version");
            write(target, "src/App.vue", "current-version");
            WorkspaceFileSystemService service = new WorkspaceFileSystemService(retryProperties(1, 0),
                    (moveSource, moveTarget, options) -> {
                        if (moveSource.getFileName().toString().startsWith(".project.restore-")) {
                            throw new IOException("staged activation failed");
                        }
                        return Files.move(moveSource, moveTarget, options);
                    });

            WorkspaceFileSystemException exception = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.replaceDirectory(source, target)
            );

            assertEquals(WorkspaceFileSystemException.Reason.REPLACE_FAILED, exception.reason());
            assertEquals("current-version", Files.readString(target.resolve("src/App.vue")));
            assertNoTemporarySibling(target, "restore");
            assertNoTemporarySibling(target, "previous");
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldRemovePartialCopyWhenResourceLimitIsExceeded() throws Exception {
        Path root = createTempWorkspace("copy-limit");
        Path source = root.resolve("source");
        Path target = root.resolve("snapshots/snapshot");
        try {
            write(source, "src/one.ts", "1");
            write(source, "src/two.ts", "2");
            WorkspaceFileSystemProperties properties = new WorkspaceFileSystemProperties();
            properties.setMaxFiles(1);
            WorkspaceFileSystemService service = new WorkspaceFileSystemService(properties);

            WorkspaceFileSystemException exception = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.copyDirectory(source, target)
            );

            assertEquals(WorkspaceFileSystemException.Reason.FILE_LIMIT_EXCEEDED, exception.reason());
            assertFalse(Files.exists(target));
            Path targetParent = target.getParent();
            try (Stream<Path> children = Files.list(targetParent)) {
                assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith(".snapshot.copy-")));
            }
        } finally {
            cleanup(root);
        }
    }

    @Test
    void copyMustRemoveStagingDirectoryWhenContinuationCheckRejectsWork() throws Exception {
        Path root = createTempWorkspace("copy-cancelled");
        Path source = root.resolve("source");
        Path target = root.resolve("snapshots/snapshot");
        try {
            write(source, "src/one.ts", "1");
            write(source, "src/two.ts", "2");
            WorkspaceFileSystemService service = serviceWithDefaults();
            AtomicInteger checks = new AtomicInteger();

            assertThrows(
                    IllegalStateException.class,
                    () -> service.copyDirectory(source, target, () -> {
                        if (checks.incrementAndGet() >= 4) {
                            throw new IllegalStateException("复制已取消");
                        }
                    })
            );

            assertFalse(Files.exists(target));
            assertNoTemporarySibling(target, "copy");
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldRejectOverlappingCopyDirectories() throws Exception {
        Path root = createTempWorkspace("overlap");
        try {
            write(root, "src/App.vue", "content");
            WorkspaceFileSystemService service = serviceWithDefaults();

            WorkspaceFileSystemException exception = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.copyDirectory(root, root.resolve("snapshot"))
            );

            assertEquals(WorkspaceFileSystemException.Reason.INVALID_PATH, exception.reason());
            assertFalse(Files.exists(root.resolve("snapshot")));
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldRejectSymbolicLinkRootAndAtomicWriteTarget() throws Exception {
        Path root = createTempWorkspace("symlink");
        Path actual = root.resolve("actual");
        Path linkedRoot = root.resolve("linked-root");
        try {
            Files.createDirectories(actual);
            createSymbolicLinkOrSkip(linkedRoot, actual);
            WorkspaceFileSystemService service = serviceWithDefaults();

            WorkspaceFileSystemException rootException = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.scanProject(linkedRoot)
            );
            assertEquals(WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK, rootException.reason());

            write(actual, "real-index.json", "{}");
            Path linkedFile = actual.resolve("semantic-index.json");
            createSymbolicLinkOrSkip(linkedFile, actual.resolve("real-index.json"));
            WorkspaceFileSystemException writeException = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.writeUtf8Atomically(actual, "semantic-index.json", "{\"safe\":true}")
            );
            assertEquals(WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK, writeException.reason());
            assertEquals("{}", Files.readString(actual.resolve("real-index.json")));
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldListBoundedTreeWithEmptyDirectoriesFilteringAndStableOrdering() throws Exception {
        Path root = createTempWorkspace("tree");
        try {
            Files.createDirectories(root.resolve("alpha/empty"));
            Files.createDirectories(root.resolve("zeta/level-two/level-three"));
            write(root, "zeta/level-two/level-three/deep.ts", "deep");
            write(root, "B.js", "b");
            write(root, "a.js", "a");
            write(root, ".git/config", "hidden");
            write(root, ".app-code-orphan.tmp", "temporary");
            WorkspaceFileSystemService service = serviceWithDefaults();

            List<WorkspaceFileSystemService.WorkspaceTreeNode> nodes = service.listTree(
                    root,
                    2,
                    (relativePath, name, directory) -> !".git".equalsIgnoreCase(name)
            );

            assertEquals(List.of("alpha", "zeta", "a.js", "B.js"),
                    nodes.stream().map(WorkspaceFileSystemService.WorkspaceTreeNode::name).toList());
            assertTrue(nodes.getFirst().children().getFirst().directory());
            WorkspaceFileSystemService.WorkspaceTreeNode levelTwo = nodes.get(1).children().getFirst();
            assertEquals("level-two", levelTwo.name());
            assertTrue(levelTwo.children().isEmpty());
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldFailExplicitlyWhenTreeCapacityIsExceeded() throws Exception {
        Path root = createTempWorkspace("tree-limit");
        try {
            write(root, "one.ts", "1");
            write(root, "two.ts", "2");
            WorkspaceFileSystemProperties properties = new WorkspaceFileSystemProperties();
            properties.setMaxFiles(1);
            WorkspaceFileSystemService service = new WorkspaceFileSystemService(properties);

            WorkspaceFileSystemException exception = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.listTree(root, 8, (relativePath, name, directory) -> true)
            );

            assertEquals(WorkspaceFileSystemException.Reason.FILE_LIMIT_EXCEEDED, exception.reason());
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldFailExplicitlyWhenTreeDirectoryCapacityIsExceeded() throws Exception {
        Path root = createTempWorkspace("tree-directory-limit");
        try {
            Files.createDirectories(root.resolve("first"));
            Files.createDirectories(root.resolve("second"));
            WorkspaceFileSystemProperties properties = new WorkspaceFileSystemProperties();
            properties.setMaxListedDirectories(1);
            WorkspaceFileSystemService service = new WorkspaceFileSystemService(properties);

            WorkspaceFileSystemException exception = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.listTree(root, 8, (relativePath, name, directory) -> true)
            );

            assertEquals(WorkspaceFileSystemException.Reason.FILE_LIMIT_EXCEEDED, exception.reason());
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldApplyConfiguredInteractiveTreeDepthEvenWhenCallerRequestsMore() throws Exception {
        Path root = createTempWorkspace("tree-depth-limit");
        try {
            write(root, "first/second/deep.ts", "deep");
            WorkspaceFileSystemProperties properties = new WorkspaceFileSystemProperties();
            properties.setMaxInteractiveTreeDepth(1);
            WorkspaceFileSystemService service = new WorkspaceFileSystemService(properties);

            List<WorkspaceFileSystemService.WorkspaceTreeNode> nodes = service.listTree(
                    root,
                    8,
                    (relativePath, name, directory) -> true
            );

            assertEquals(List.of("first"),
                    nodes.stream().map(WorkspaceFileSystemService.WorkspaceTreeNode::name).toList());
            assertTrue(nodes.getFirst().children().isEmpty());
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldApplyConfiguredInteractiveFileLimitEvenWhenCallerRequestsMore() throws Exception {
        Path root = createTempWorkspace("interactive-file-limit");
        try {
            String oversizedContent = "x".repeat(1_025);
            write(root, "src/App.vue", oversizedContent);
            WorkspaceFileSystemProperties properties = new WorkspaceFileSystemProperties();
            properties.setMaxInteractiveFileBytes(1_024);
            properties.setMaxFileBytes(2_048);
            WorkspaceFileSystemService service = new WorkspaceFileSystemService(properties);
            WorkspaceFileSystemService.WorkspaceFileMetadata file =
                    service.resolveExistingFile(root, "src/App.vue");

            WorkspaceFileSystemException readFailure = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.readUtf8(root, file, 2_048)
            );
            WorkspaceFileSystemException writeFailure = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.replaceUtf8Atomically(root, file, oversizedContent, 2_048)
            );

            assertEquals(WorkspaceFileSystemException.Reason.FILE_TOO_LARGE, readFailure.reason());
            assertEquals(WorkspaceFileSystemException.Reason.FILE_TOO_LARGE, writeFailure.reason());
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldResolveReadAndCompareReplaceExistingFile() throws Exception {
        Path root = createTempWorkspace("replace-file");
        try {
            write(root, "src/App.vue", "before");
            WorkspaceFileSystemService service = serviceWithDefaults();
            WorkspaceFileSystemService.WorkspaceFileMetadata original =
                    service.resolveExistingFile(root, "src/App.vue");

            assertEquals("before", service.readUtf8(root, original, 1_024));
            WorkspaceFileSystemService.WorkspaceFileMetadata replaced =
                    service.replaceUtf8Atomically(root, original, "after", 1_024);

            assertEquals("after", Files.readString(root.resolve("src/App.vue")));
            assertEquals("after", service.readUtf8(root, replaced, 1_024));
            WorkspaceFileSystemException staleWrite = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.replaceUtf8Atomically(root, original, "stale", 1_024)
            );
            assertEquals(WorkspaceFileSystemException.Reason.FILE_CHANGED, staleWrite.reason());
            assertEquals("after", Files.readString(root.resolve("src/App.vue")));
            try (Stream<Path> children = Files.list(root.resolve("src"))) {
                assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith(".app-code-")));
            }
        } finally {
            cleanup(root);
        }
    }

    @Test
    void shouldClassifyMissingAndEscapingInteractiveFiles() throws Exception {
        Path root = createTempWorkspace("resolve-file");
        try {
            WorkspaceFileSystemService service = serviceWithDefaults();

            WorkspaceFileSystemException missing = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.resolveExistingFile(root, "missing.ts")
            );
            WorkspaceFileSystemException escaping = assertThrows(
                    WorkspaceFileSystemException.class,
                    () -> service.resolveExistingFile(root, "../outside.ts")
            );

            assertEquals(WorkspaceFileSystemException.Reason.MISSING_FILE, missing.reason());
            assertEquals(WorkspaceFileSystemException.Reason.INVALID_PATH, escaping.reason());
        } finally {
            cleanup(root);
        }
    }

    private WorkspaceFileSystemProperties retryProperties(int maxAttempts, long retryDelayMillis) {
        WorkspaceFileSystemProperties properties = new WorkspaceFileSystemProperties();
        properties.setPublishMaxAttempts(maxAttempts);
        properties.setPublishRetryDelayMillis(retryDelayMillis);
        return properties;
    }

    private AccessDeniedException accessDenied(Path source, Path target) {
        return new AccessDeniedException(source.toString(), target.toString(), "temporarily locked");
    }

    private void assertNoTemporarySibling(Path target, String purpose) throws IOException {
        String prefix = "." + target.getFileName() + "." + purpose + "-";
        try (Stream<Path> children = Files.list(target.getParent())) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith(prefix)));
        }
    }

    private WorkspaceFileSystemService serviceWithDefaults() {
        return new WorkspaceFileSystemService(new WorkspaceFileSystemProperties());
    }

    private Path createTempWorkspace(String prefix) throws IOException {
        Path parent = Path.of(System.getProperty("java.io.tmpdir"), "ai-code-mother-tests");
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, "workspace-fs-" + prefix + "-");
    }

    private void write(Path root, String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void cleanup(Path root) {
        if (root == null) {
            return;
        }
        try {
            FileUtil.del(root.toFile());
        } catch (Exception ignored) {
        }
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "当前环境不支持创建符号链接: " + exception.getClass().getSimpleName());
        }
    }
}
