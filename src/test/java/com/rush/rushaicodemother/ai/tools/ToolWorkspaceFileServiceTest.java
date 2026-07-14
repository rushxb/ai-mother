package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.config.AiToolWorkspaceProperties;
import com.rush.rushaicodemother.constant.AppConstant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolWorkspaceFileServiceTest {

    private final List<Path> cleanupPaths = new ArrayList<>();

    @AfterEach
    void cleanup() throws IOException {
        for (Path path : cleanupPaths) {
            deleteRecursively(path);
        }
    }

    @Test
    void shouldReadUtf8AndRejectTraversal() throws Exception {
        long appId = 991_001L;
        Path root = prepareProject(appId);
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.vue"), "你好，workspace");
        ToolWorkspaceFileService service = ToolPathSupportTestFixture.workspaceForApp(appId);

        ToolWorkspaceFileService.ToolWorkspaceFile file = service.resolveFile(appId, "src\\App.vue");

        assertEquals("src/App.vue", file.relativePath());
        assertEquals("你好，workspace", service.readUtf8(file));
        ToolInputException exception = assertThrows(
                ToolInputException.class,
                () -> service.resolveFile(appId, "../outside.txt")
        );
        assertFalse(exception.publicMessage().contains(root.toString()));
    }

    @Test
    void shouldEnforceToolSpecificReadLimit() throws Exception {
        long appId = 991_002L;
        Path root = prepareProject(appId);
        Files.writeString(root.resolve("large.txt"), "x".repeat(2_048));
        AiToolWorkspaceProperties properties = new AiToolWorkspaceProperties();
        properties.setMaxReadableFileBytes(1_024);
        ToolWorkspaceFileService service = ToolPathSupportTestFixture.workspaceForApp(appId, properties);

        ToolInputException exception = assertThrows(
                ToolInputException.class,
                () -> service.readUtf8(service.resolveFile(appId, "large.txt"))
        );

        assertTrue(exception.publicMessage().contains("大小限制"));
    }

    @Test
    void resolvedFileReplacedWithSymbolicLinkMustBeRejected() throws Exception {
        long appId = 991_003L;
        Path root = prepareProject(appId);
        Path target = root.resolve("target.txt");
        Files.writeString(target, "inside");
        Path outside = Files.createTempFile("tool-workspace-outside", ".txt");
        cleanupPaths.add(outside);
        Files.writeString(outside, "outside");
        ToolWorkspaceFileService service = ToolPathSupportTestFixture.workspaceForApp(appId);
        ToolWorkspaceFileService.ToolWorkspaceFile resolved = service.resolveFile(appId, "target.txt");
        Files.delete(target);
        createSymbolicLinkOrSkip(target, outside);

        assertThrows(ToolInputException.class, () -> service.readUtf8(resolved));
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void directoryListingMustEnforceEntryLimit() throws Exception {
        long appId = 991_004L;
        Path root = prepareProject(appId);
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("b.txt"), "b");
        Files.writeString(root.resolve("c.txt"), "c");
        AiToolWorkspaceProperties properties = new AiToolWorkspaceProperties();
        properties.setMaxDirectoryEntries(2);
        ToolWorkspaceFileService service = ToolPathSupportTestFixture.workspaceForApp(appId, properties);

        ToolWorkspaceFileService.DirectoryListing listing = service.listDirectory(appId, null);

        assertEquals(2, listing.entries().size());
        assertTrue(listing.truncated());
    }

    @Test
    void directoryListingMustEnforceDepthAndIgnoreSensitiveOrGeneratedPaths() throws Exception {
        long appId = 991_005L;
        Path root = prepareProject(appId);
        Files.createDirectories(root.resolve("src/feature/deep"));
        Files.writeString(root.resolve("src/feature/deep/App.vue"), "app");
        Files.createDirectories(root.resolve("node_modules/pkg"));
        Files.writeString(root.resolve("node_modules/pkg/index.js"), "secret");
        Files.writeString(root.resolve(".env.production"), "TOKEN=secret");
        AiToolWorkspaceProperties properties = new AiToolWorkspaceProperties();
        properties.setMaxDirectoryDepth(2);
        ToolWorkspaceFileService service = ToolPathSupportTestFixture.workspaceForApp(appId, properties);

        ToolWorkspaceFileService.DirectoryListing listing = service.listDirectory(appId, null);
        List<String> paths = listing.entries().stream()
                .map(ToolWorkspaceFileService.DirectoryEntry::relativePath)
                .toList();

        assertTrue(paths.contains("src"));
        assertTrue(paths.contains("src/feature"));
        assertFalse(paths.stream().anyMatch(path -> path.contains("deep")));
        assertFalse(paths.stream().anyMatch(path -> path.contains("node_modules")));
        assertFalse(paths.stream().anyMatch(path -> path.startsWith(".env")));
        assertTrue(listing.truncated());
    }

    @Test
    void directoryListingMustSkipSymbolicLinks() throws Exception {
        long appId = 991_006L;
        Path root = prepareProject(appId);
        Path outside = Files.createTempDirectory("tool-workspace-linked-directory");
        cleanupPaths.add(outside);
        Files.writeString(outside.resolve("secret.txt"), "secret");
        createSymbolicLinkOrSkip(root.resolve("external"), outside);
        ToolWorkspaceFileService service = ToolPathSupportTestFixture.workspaceForApp(appId);

        ToolWorkspaceFileService.DirectoryListing listing = service.listDirectory(appId, null);

        assertFalse(listing.entries().stream().anyMatch(entry -> entry.relativePath().contains("external")));
    }

    @Test
    void shouldResolveNestedDirectoryAndPreventChildTraversal() throws Exception {
        long appId = 991_007L;
        Path root = prepareProject(appId);
        Files.createDirectories(root.resolve("frontend"));
        Files.writeString(root.resolve("frontend/package.json"), "{\"name\":\"frontend\"}");
        ToolWorkspaceFileService service = ToolPathSupportTestFixture.workspaceForApp(appId);

        ToolWorkspaceFileService.ToolWorkspaceDirectory directory =
                service.resolveDirectory(appId, "frontend");
        ToolWorkspaceFileService.ToolWorkspaceFile packageJson =
                service.resolveFile(directory, "package.json");

        assertEquals("frontend", directory.displayPath());
        assertEquals(root.resolve("frontend").toRealPath(), directory.absolutePath());
        assertEquals("{\"name\":\"frontend\"}", service.readUtf8(packageJson));
        assertThrows(ToolInputException.class, () -> service.resolveFile(directory, "../secret.txt"));
    }

    private Path prepareProject(long appId) throws IOException {
        Path root = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId)
                .toAbsolutePath()
                .normalize();
        deleteRecursively(root);
        Files.createDirectories(root);
        cleanupPaths.add(root);
        return root;
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.abort("当前平台不允许创建符号链接: " + exception.getMessage());
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root)) {
            Files.deleteIfExists(root);
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
