package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.config.EditLocatorProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditWorkspaceFileServiceTest {

    @Test
    void shouldTerminateTraversalAtConfiguredFileLimit() throws Exception {
        Path root = cleanTestRoot("scan-limit");
        Path sourceDirectory = Files.createDirectories(root.resolve("src"));
        for (int index = 0; index < 130; index++) {
            Files.writeString(sourceDirectory.resolve("File%03d.ts".formatted(index)), "export const value = true;");
        }
        EditLocatorProperties properties = new EditLocatorProperties();
        properties.setMaxScannedFiles(100);
        EditWorkspaceFileService service = new EditWorkspaceFileService(properties);

        var files = service.scanIndexableFiles(workspace(root), "");

        assertEquals(100, files.size());
        assertEquals(files.stream().map(EditWorkspaceFile::relativePath).sorted().toList(),
                files.stream().map(EditWorkspaceFile::relativePath).toList());
    }

    @Test
    void shouldSkipOversizedFileBeforeReadingIt() throws Exception {
        Path root = cleanTestRoot("oversized-read");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Large.ts"), "x".repeat(1025));
        EditLocatorProperties properties = new EditLocatorProperties();
        properties.setMaxReadableFileBytes(1024);
        EditWorkspaceFileService service = new EditWorkspaceFileService(properties);

        var file = service.resolveEditableFile(workspace(root), "src/Large.ts").orElseThrow();

        assertTrue(service.readUtf8(workspace(root), file).isEmpty());
    }

    @Test
    void shouldExcludeHiddenAndGeneratedDirectories() throws Exception {
        Path root = cleanTestRoot("hidden-directories");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("node_modules/package"));
        Files.createDirectories(root.resolve(".ai-code-index"));
        Files.writeString(root.resolve("src/Safe.ts"), "export const safe = true;");
        Files.writeString(root.resolve("node_modules/package/Unsafe.ts"), "export const unsafe = true;");
        Files.writeString(root.resolve(".ai-code-index/Unsafe.ts"), "export const unsafe = true;");

        EditWorkspaceFileService service = new EditWorkspaceFileService(new EditLocatorProperties());

        var files = service.scanIndexableFiles(workspace(root), "");

        assertEquals(java.util.List.of("src/Safe.ts"),
                files.stream().map(EditWorkspaceFile::relativePath).toList());
    }

    @Test
    void shouldExcludeExternalSymbolicLink() throws Exception {
        Path root = cleanTestRoot("external-symlink");
        Files.createDirectories(root.resolve("src"));
        Path outsideFile = root.getParent().resolve("outside-file-service.ts");
        Files.writeString(outsideFile, "export const secret = true;");
        Path link = root.resolve("src/Linked.ts");
        try {
            createSymbolicLinkOrSkip(link, outsideFile);
            EditWorkspaceFileService service = new EditWorkspaceFileService(new EditLocatorProperties());

            var files = service.scanIndexableFiles(workspace(root), "");

            assertTrue(files.isEmpty());
            assertTrue(service.resolveEditableFile(workspace(root), "src/Linked.ts").isEmpty());
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outsideFile);
        }
    }

    private Path cleanTestRoot(String caseName) {
        Path root = Path.of("target", "test-workspaces", "edit-workspace-file", caseName)
                .toAbsolutePath()
                .normalize();
        FileUtil.del(root.toFile());
        return root;
    }

    private GenerationWorkspace workspace(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        return new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.VUE_PROJECT,
                normalizedRoot,
                normalizedRoot,
                true,
                normalizedRoot,
                null,
                GenerationWorkspaceService.HIDDEN_FILE_NAMES,
                GenerationWorkspaceService.EDITABLE_EXTENSIONS
        );
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable in this test environment: " + e.getMessage());
        }
    }
}
