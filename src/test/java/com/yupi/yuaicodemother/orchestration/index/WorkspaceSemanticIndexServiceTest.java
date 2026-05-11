package com.yupi.yuaicodemother.orchestration.index;

import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSemanticIndexServiceTest {

    private final WorkspaceSemanticIndexService service = new WorkspaceSemanticIndexService();

    @Test
    void shouldPersistAndSearchWorkspaceIndex() throws Exception {
        Path tempDir = createTempWorkspace();
        try {
            write(tempDir, "src/views/Login.vue", "<template>login token form</template>");
            write(tempDir, "src/components/UserTable.vue", "<template>用户列表分页</template>");
            write(tempDir, "node_modules/ignored/index.js", "login should be ignored");

            WorkspaceSemanticIndex index = service.loadOrBuild(tempDir);
            List<WorkspaceSemanticSearchHit> hits = service.search(tempDir, "login token", Set.of("vue"), 10);

            assertEquals(2, index.indexedFileCount());
            assertTrue(Files.exists(tempDir.resolve(".ai-code-index/semantic-index.json")));
            assertFalse(hits.isEmpty());
            assertEquals("src/views/Login.vue", hits.get(0).relativePath());
            assertTrue(hits.get(0).score() > 0);
        } finally {
            cleanup(tempDir);
        }
    }

    @Test
    void shouldRefreshIndexWhenWorkspaceChanges() throws Exception {
        Path tempDir = createTempWorkspace();
        try {
            write(tempDir, "src/App.vue", "<template>home</template>");

            WorkspaceSemanticIndex firstIndex = service.loadOrBuild(tempDir);
            write(tempDir, "src/router/index.ts", "export const routes = []");
            WorkspaceSemanticIndex refreshedIndex = service.loadOrBuild(tempDir);

            assertEquals(1, firstIndex.indexedFileCount());
            assertEquals(2, refreshedIndex.indexedFileCount());
            assertTrue(service.suggestFiles(tempDir, "routes", 5).contains("src/router/index.ts"));
        } finally {
            cleanup(tempDir);
        }
    }

    private Path createTempWorkspace() throws Exception {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ai-code-mother-tests");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "workspace-index-");
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
