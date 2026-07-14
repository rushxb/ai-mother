package com.rush.rushaicodemother.orchestration.codegraph;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemTestFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SymbolIndexServiceTest {

    private final WorkspaceCodeGraphService graphService = new WorkspaceCodeGraphService(
            new CodeGraphAstParser(new StructuredSyntaxValidationService()),
            WorkspaceFileSystemTestFactory.create()
    );
    private final SymbolIndexService service = new SymbolIndexService(graphService);

    @Test
    void shouldSearchSymbolIndex() throws Exception {
        Path root = createTempWorkspace();
        try {
            write(root, "src/stores/user.ts", "export const loadUsers = () => []");

            assertFalse(service.search(root, "loadUsers", 5).isEmpty());
            assertEquals("src/stores/user.ts", service.search(root, "loadUsers", 5).get(0).relativePath());
        } finally {
            cleanup(root);
        }
    }

    private Path createTempWorkspace() throws Exception {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ai-code-mother-tests");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "symbol-index-");
    }

    private void write(Path rootDir, String relativePath, String content) throws Exception {
        Path file = rootDir.resolve(relativePath);
        Files.createDirectories(file.getParent() == null ? rootDir : file.getParent());
        Files.writeString(file, content);
    }

    private void cleanup(Path path) {
        if (path == null) {
            return;
        }
        try {
            FileUtil.del(path.toFile());
        } catch (Exception ignored) {
        }
    }
}
