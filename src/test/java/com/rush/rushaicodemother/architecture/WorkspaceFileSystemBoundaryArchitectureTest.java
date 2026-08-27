package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents indexing, code-graph, and snapshot modules from bypassing the bounded file-system boundary. */
class WorkspaceFileSystemBoundaryArchitectureTest {

    private static final Path JAVA_SOURCE_ROOT = Path.of("src", "main", "java");

    private static final List<String> DIRECT_FILE_SYSTEM_CONSUMERS = List.of(
            "com/rush/rushaicodemother/orchestration/index/WorkspaceSemanticIndexService.java",
            "com/rush/rushaicodemother/orchestration/codegraph/WorkspaceCodeGraphService.java",
            "com/rush/rushaicodemother/ai/tools/DiffSummaryTool.java",
            "com/rush/rushaicodemother/orchestration/snapshot/GenerationRollbackPointService.java",
            "com/rush/rushaicodemother/orchestration/snapshot/GenerationRollbackRestoreService.java",
            "com/rush/rushaicodemother/orchestration/snapshot/GenerationDiffSummaryService.java",
            "com/rush/rushaicodemother/orchestration/snapshot/GenerationSnapshotWorkspaceService.java"
    );

    private static final String SNAPSHOT_ROLLBACK_TOOL =
            "com/rush/rushaicodemother/ai/tools/SnapshotRollbackTool.java";

    private static final Map<String, String> FORBIDDEN_ACCESS = Map.ofEntries(
            Map.entry("Files.walk(", "unbounded directory traversal"),
            Map.entry("Files.list(", "unbounded directory listing"),
            Map.entry("Files.readString(", "unbounded JDK file read"),
            Map.entry("Files.copy(", "direct non-transactional copy"),
            Map.entry("Files.createDirectories(", "direct directory mutation"),
            Map.entry("Files.exists(", "workspace-bypassing existence check"),
            Map.entry("Files.isDirectory(", "workspace-bypassing directory check"),
            Map.entry("Files.isRegularFile(", "workspace-bypassing file type check"),
            Map.entry("Files.size(", "workspace-bypassing size check"),
            Map.entry("Files.getLastModifiedTime(", "workspace-bypassing metadata check"),
            Map.entry("FileUtil.readString(", "unbounded Hutool file read"),
            Map.entry("FileUtil.readLines(", "unbounded Hutool line read"),
            Map.entry("FileUtil.contentEquals(", "workspace-bypassing content comparison"),
            Map.entry("FileUtil.del(", "direct recursive deletion"),
            Map.entry("FileUtil.writeString(", "direct non-atomic write"),
            Map.entry(".toFile()", "symbolic-link-following java.io.File access"),
            Map.entry("ProjectWorkspaceSupport", "deleted legacy workspace helper")
    );

    @Test
    void consumersMustUseWorkspaceFileSystemService() throws IOException {
        for (String relativePath : DIRECT_FILE_SYSTEM_CONSUMERS) {
            Path sourceFile = JAVA_SOURCE_ROOT.resolve(relativePath);
            String source = Files.readString(sourceFile);
            assertTrue(
                    source.contains("WorkspaceFileSystemService"),
                    () -> relativePath + " must depend on WorkspaceFileSystemService"
            );
            for (Map.Entry<String, String> forbidden : FORBIDDEN_ACCESS.entrySet()) {
                assertFalse(
                        source.contains(forbidden.getKey()),
                        () -> relativePath + " contains " + forbidden.getValue() + ": " + forbidden.getKey()
                );
            }
        }
    }

    @Test
    void snapshotRollbackToolMustUseCanonicalSnapshotBoundary() throws IOException {
        String source = Files.readString(JAVA_SOURCE_ROOT.resolve(SNAPSHOT_ROLLBACK_TOOL));

        assertTrue(
                source.contains("GenerationSnapshotWorkspaceService"),
                "SnapshotRollbackTool must resolve snapshots through GenerationSnapshotWorkspaceService"
        );
        assertFalse(
                source.contains("WorkspaceFileSystemService"),
                "SnapshotRollbackTool must not bypass the canonical snapshot boundary"
        );
        assertNoForbiddenAccess(SNAPSHOT_ROLLBACK_TOOL, source);
    }

    @Test
    void codeGraphParserMustRemainFileSystemIndependent() throws IOException {
        String relativePath = "com/rush/rushaicodemother/orchestration/codegraph/CodeGraphAstParser.java";
        String source = Files.readString(JAVA_SOURCE_ROOT.resolve(relativePath));
        assertNoForbiddenAccess(relativePath, source);
    }

    private void assertNoForbiddenAccess(String relativePath, String source) {
        for (Map.Entry<String, String> forbidden : FORBIDDEN_ACCESS.entrySet()) {
            assertFalse(
                    source.contains(forbidden.getKey()),
                    () -> relativePath + " contains " + forbidden.getValue() + ": " + forbidden.getKey()
            );
        }
    }

    @Test
    void generationCommitMustUseDedicatedWorkspaceAndGitTransactionBoundaries() throws IOException {
        String relativePath =
                "com/rush/rushaicodemother/orchestration/snapshot/GenerationCommitService.java";
        String source = Files.readString(JAVA_SOURCE_ROOT.resolve(relativePath));

        assertTrue(source.contains("WorkspaceFileSystemService"));
        assertTrue(source.contains("GitTransactionResourceManager"));
        assertFalse(Pattern.compile("\\bFiles\\.").matcher(source).find());
        assertFalse(Pattern.compile("\\bFileUtil\\.").matcher(source).find());
        assertFalse(source.contains(".toFile()"));
        assertFalse(source.contains("toRealPath("));
    }
}
