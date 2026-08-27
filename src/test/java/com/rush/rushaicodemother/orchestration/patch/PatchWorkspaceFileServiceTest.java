package com.rush.rushaicodemother.orchestration.patch;

import com.rush.rushaicodemother.config.PatchExecutionProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PatchWorkspaceFileServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "D:src/App.vue",
            "d:src/App.vue",
            "D:src\\App.vue",
            "d:src\\App.vue"
    })
    void driveRelativePathMustBeRejectedBeforeAnyWorkspaceMutation(String driveRelativePath) throws Exception {
        Path root = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "patch-workspace-drive-relative-");
        PatchWorkspaceFileService service = service(new PatchExecutionProperties());
        try {
            PatchWorkspaceException exception = assertThrows(
                    PatchWorkspaceException.class,
                    () -> service.writeNewUtf8(service.resolve(root, driveRelativePath), "content")
            );

            assertEquals("path_outside_project", exception.reason());
            try (var entries = Files.list(root)) {
                assertEquals(0L, entries.count(), "非法路径不得创建任何工作区文件或目录");
            }
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void shouldPreserveWhitespaceWhenWritingUtf8() throws Exception {
        Path root = Files.createTempDirectory("patch-workspace-whitespace");
        PatchWorkspaceFileService service = service(new PatchExecutionProperties());
        PatchWorkspaceTarget target = service.resolve(root, "src/blank.txt");

        service.writeNewUtf8(target, "   \n");

        assertEquals("   \n", service.readUtf8(target));
    }

    @Test
    void shouldRejectParentTraversal() throws Exception {
        Path root = Files.createTempDirectory("patch-workspace-traversal");
        PatchWorkspaceFileService service = service(new PatchExecutionProperties());

        PatchWorkspaceException exception = assertThrows(
                PatchWorkspaceException.class,
                () -> service.resolve(root, "src/../outside.txt")
        );

        assertEquals("path_outside_project", exception.reason());
    }

    @Test
    void shouldRejectSymbolicLinkFile() throws Exception {
        Path root = Files.createTempDirectory("patch-workspace-file-link");
        Path outside = Files.createTempFile("patch-outside", ".txt");
        Files.writeString(outside, "outside");
        Path link = root.resolve("linked.txt");
        createSymbolicLinkOrSkip(link, outside);
        PatchWorkspaceFileService service = service(new PatchExecutionProperties());

        PatchWorkspaceException exception = assertThrows(
                PatchWorkspaceException.class,
                () -> service.resolve(root, "linked.txt")
        );

        assertEquals("symbolic_link_not_allowed", exception.reason());
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void shouldRejectSymbolicLinkDirectory() throws Exception {
        Path root = Files.createTempDirectory("patch-workspace-dir-link");
        Path outside = Files.createTempDirectory("patch-outside-dir");
        Path link = root.resolve("external");
        createSymbolicLinkOrSkip(link, outside);
        PatchWorkspaceFileService service = service(new PatchExecutionProperties());

        PatchWorkspaceException exception = assertThrows(
                PatchWorkspaceException.class,
                () -> service.resolve(root, "external/secret.txt")
        );

        assertEquals("symbolic_link_not_allowed", exception.reason());
        assertFalse(Files.exists(outside.resolve("secret.txt")));
    }

    @Test
    void shouldRejectMalformedUtf8() throws Exception {
        Path root = Files.createTempDirectory("patch-workspace-invalid-utf8");
        Files.write(root.resolve("invalid.txt"), new byte[]{(byte) 0xC3, (byte) 0x28});
        PatchWorkspaceFileService service = service(new PatchExecutionProperties());

        PatchWorkspaceException exception = assertThrows(
                PatchWorkspaceException.class,
                () -> service.readUtf8(service.resolve(root, "invalid.txt"))
        );

        assertEquals("invalid_utf8_content", exception.reason());
    }
    @Test
    void shouldRejectOversizedExistingFile() throws Exception {
        Path root = Files.createTempDirectory("patch-workspace-read-limit");
        Files.writeString(root.resolve("large.txt"), "x".repeat(1_025));
        PatchExecutionProperties properties = new PatchExecutionProperties();
        properties.setMaxReadableFileBytes(1_024);
        PatchWorkspaceFileService service = service(properties);

        PatchWorkspaceException exception = assertThrows(
                PatchWorkspaceException.class,
                () -> service.readUtf8(service.resolve(root, "large.txt"))
        );

        assertEquals("target_file_too_large", exception.reason());
    }

    @Test
    void callerReadLimitMustNotBeAbleToExceedOrBypassResourcePolicy() throws Exception {
        Path root = Files.createTempDirectory("patch-workspace-caller-read-limit");
        Files.writeString(root.resolve("large.txt"), "x".repeat(2_048));
        PatchExecutionProperties properties = new PatchExecutionProperties();
        properties.setMaxReadableFileBytes(4_096);
        PatchWorkspaceFileService service = service(properties);
        PatchWorkspaceTarget target = service.resolve(root, "large.txt");

        PatchWorkspaceException callerLimitException = assertThrows(
                PatchWorkspaceException.class,
                () -> service.readUtf8(target, 1_024)
        );
        PatchWorkspaceException invalidLimitException = assertThrows(
                PatchWorkspaceException.class,
                () -> service.readUtf8(target, 0)
        );

        assertEquals("target_file_too_large", callerLimitException.reason());
        assertEquals("invalid_read_limit", invalidLimitException.reason());
    }

    @Test
    void directoryCheckMustNotFollowSymbolicLinks() throws Exception {
        Path root = Files.createTempDirectory("patch-workspace-directory-check");
        Files.createDirectories(root.resolve("real"));
        Path link = root.resolve("linked");
        createSymbolicLinkOrSkip(link, root.resolve("real"));
        PatchWorkspaceFileService service = service(new PatchExecutionProperties());

        assertThrows(PatchWorkspaceException.class, () -> service.resolve(root, "linked"));
        assertFalse(service.isDirectory(service.resolve(root, "missing")));
    }

    @Test
    void shouldRejectOversizedOutputBeforeCreatingFile() throws Exception {
        Path root = Files.createTempDirectory("patch-workspace-write-limit");
        PatchExecutionProperties properties = new PatchExecutionProperties();
        properties.setMaxWrittenFileBytes(1_024);
        PatchWorkspaceFileService service = service(properties);
        PatchWorkspaceTarget target = service.resolve(root, "large.txt");

        PatchWorkspaceException exception = assertThrows(
                PatchWorkspaceException.class,
                () -> service.writeNewUtf8(target, "x".repeat(1_025))
        );

        assertEquals("output_file_too_large", exception.reason());
        assertFalse(Files.exists(root.resolve("large.txt")));
    }

    private PatchWorkspaceFileService service(PatchExecutionProperties properties) {
        return new PatchWorkspaceFileService(properties);
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
    }
}

