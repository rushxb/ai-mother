package com.rush.rushaicodemother.orchestration.index;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemTestFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class WorkspaceSemanticIndexServiceTest {

    private final WorkspaceSemanticIndexService service = new WorkspaceSemanticIndexService(
            WorkspaceFileSystemTestFactory.create()
    );

    @Test
    void shouldPersistAndSearchWorkspaceIndex() throws Exception {
        Path tempDir = createTempWorkspace();
        try {
            write(tempDir, "src/views/Login.vue", """
                    <template>login token form</template>
                    <script setup>
                    export function issueLoginToken() {}
                    </script>
                    """);
            write(tempDir, "src/components/UserTable.vue", "<template>用户列表分页</template>");
            write(tempDir, "node_modules/ignored/index.js", "login should be ignored");

            WorkspaceSemanticIndex index = service.loadOrBuild(tempDir);
            List<WorkspaceSemanticSearchHit> hits = service.search(tempDir, "login token", Set.of("vue"), 10);
            List<WorkspaceSemanticSearchHit> symbolHits = service.search(tempDir, "issueLoginToken", Set.of("vue"), 10);

            assertEquals(2, index.indexedFileCount());
            assertTrue(Files.exists(tempDir.resolve(".ai-code-index/semantic-index.json")));
            assertTrue(service.countIndexedSymbols(tempDir) > 0);
            assertTrue(index.entries().stream()
                    .flatMap(entry -> entry.symbols().stream())
                    .anyMatch("issueLoginToken"::equals));
            assertFalse(hits.isEmpty());
            assertEquals("src/views/Login.vue", hits.get(0).relativePath());
            assertTrue(hits.get(0).score() > 0);
            assertFalse(symbolHits.isEmpty());
            assertEquals("symbol", symbolHits.get(0).matchType());
            assertTrue(symbolHits.get(0).matchedSymbols().contains("issueLoginToken"));
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

    @Test
    void snapshotQueriesMustNotRescanWorkspace() throws Exception {
        Path tempDir = createTempWorkspace();
        WorkspaceFileSystemService fileSystemService = spy(WorkspaceFileSystemTestFactory.create());
        WorkspaceSemanticIndexService snapshotService = new WorkspaceSemanticIndexService(fileSystemService);
        try {
            write(tempDir, "src/views/Login.vue", "<template>login token</template>");
            write(tempDir, "src/router/index.ts", "export const routes = []");
            WorkspaceSemanticIndex index = snapshotService.loadOrBuild(tempDir);
            clearInvocations(fileSystemService);

            assertEquals(2, snapshotService.indexedFileCount(index));
            assertTrue(snapshotService.indexedSymbolCount(index) >= 0);
            assertFalse(snapshotService.suggestFilesFromSnapshot(index, "login", 5).isEmpty());
            assertFalse(snapshotService.findMatchingFilesFromSnapshot(
                    index, List.of("routes"), 5).isEmpty());
            assertEquals(1, snapshotService.describeFilesFromSnapshot(
                    index, List.of("src/views/Login.vue")).size());
            assertFalse(snapshotService.searchSnapshot(index, "token", Set.of("vue"), 5).isEmpty());

            verify(fileSystemService, never()).scanProject(any(Path.class));
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
