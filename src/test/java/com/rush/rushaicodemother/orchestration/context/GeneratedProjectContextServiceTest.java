package com.rush.rushaicodemother.orchestration.context;

import com.rush.rushaicodemother.config.GenerationProjectContextProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.orchestration.context.GeneratedProjectContextService.ProjectFileContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedProjectContextServiceTest {

    private Path tempDirectory;

    @BeforeEach
    void setUp() throws Exception {
        tempDirectory = Path.of(
                "target",
                "test-workspaces",
                "generated-project-context",
                UUID.randomUUID().toString()
        ).toAbsolutePath().normalize();
        Files.createDirectories(tempDirectory);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (!Files.exists(tempDirectory)) {
            return;
        }
        try (var paths = Files.walk(tempDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void shouldReadOnlySelectedSourceFilesThroughWorkspaceBoundary() throws Exception {
        Path projectRoot = tempDirectory.resolve("project");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.createDirectories(projectRoot.resolve("node_modules"));
        Files.writeString(projectRoot.resolve("src/App.vue"),
                "<template><main>safe-context</main></template>", StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve("node_modules/secret.ts"),
                "must-not-leak", StandardCharsets.UTF_8);

        List<ProjectFileContext> contexts = service(new GenerationProjectContextProperties())
                .readSelectedFiles(projectRoot, List.of("src/App.vue", "node_modules/secret.ts"));

        assertEquals(1, contexts.size());
        assertEquals("src/App.vue", contexts.getFirst().relativePath());
        assertTrue(contexts.getFirst().content().contains("safe-context"));
        assertFalse(contexts.getFirst().content().contains("must-not-leak"));
    }

    @Test
    void shouldExcludeEnvironmentAndTraversalPaths() throws Exception {
        Path projectRoot = tempDirectory.resolve("sensitive-project");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/App.vue"),
                "public-context", StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve(".env.example"),
                "DATABASE_PASSWORD=example-secret", StandardCharsets.UTF_8);

        List<ProjectFileContext> contexts = service(new GenerationProjectContextProperties())
                .readSelectedFiles(projectRoot, List.of(
                        ".env.example", "../sensitive-project/.env.example", "src/App.vue"));

        assertEquals(1, contexts.size());
        assertEquals("src/App.vue", contexts.getFirst().relativePath());
    }

    @Test
    void shouldEnforceSingleFileAndTotalReadBudgets() throws Exception {
        Path projectRoot = tempDirectory.resolve("budgeted-project");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/App.vue"),
                "x".repeat(4_000), StandardCharsets.UTF_8);

        GenerationProjectContextProperties properties = new GenerationProjectContextProperties();
        properties.setMaxSingleFileChars(1_024);
        properties.setMaxTotalContextChars(1_100);
        List<ProjectFileContext> contexts = service(properties)
                .readSelectedFiles(projectRoot, List.of("src/App.vue"));

        assertEquals(1, contexts.size());
        assertEquals(1_024, contexts.getFirst().content().length());
        assertTrue(contexts.getFirst().truncated());
        assertTrue(contexts.getFirst().content().contains("文件内容已按读取预算截断"));
    }

    @Test
    void formattedSectionsMustStayWithinBudgetAndCloseAdaptiveFence() throws Exception {
        Path projectRoot = tempDirectory.resolve("fenced-project");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/App.vue"),
                String.valueOf((char) 96).repeat(8) + "x".repeat(4_000), StandardCharsets.UTF_8);
        GenerationProjectContextProperties properties = new GenerationProjectContextProperties();
        properties.setMaxSingleFileChars(2_000);
        properties.setMaxTotalContextChars(2_100);

        String sections = service(properties).buildSelectedFileSections(
                projectRoot, List.of("src/App.vue"), 200);

        assertTrue(sections.length() <= 1_900);
        assertTrue(sections.endsWith("~~~"));
        assertTrue(sections.contains("文件内容已按读取预算截断"));
    }

    @Test
    void shouldSkipSelectedFileThatExceedsBoundedReadLimit() throws Exception {
        Path projectRoot = tempDirectory.resolve("oversized-project");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("index.html"),
                "x".repeat(2_048), StandardCharsets.UTF_8);
        GenerationProjectContextProperties properties = new GenerationProjectContextProperties();
        properties.setMaxReadableFileBytes(1_024);

        List<ProjectFileContext> contexts = service(properties)
                .readSelectedFiles(projectRoot, List.of("index.html"));

        assertTrue(contexts.isEmpty());
    }

    @Test
    void shouldReturnNoFilesWhenWorkspaceDoesNotExist() {
        List<ProjectFileContext> contexts = service(new GenerationProjectContextProperties())
                .readSelectedFiles(tempDirectory.resolve("missing"), List.of("src/App.vue"));

        assertTrue(contexts.isEmpty());
    }

    private GeneratedProjectContextService service(GenerationProjectContextProperties properties) {
        return new GeneratedProjectContextService(
                new WorkspaceFileSystemService(new WorkspaceFileSystemProperties()),
                properties
        );
    }
}
