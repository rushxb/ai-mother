package com.rush.rushaicodemother.orchestration.context;

import com.rush.rushaicodemother.config.GenerationProjectContextProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeneratedProjectContextServiceTest {

    private static final long APP_ID = 77L;

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
    void shouldBuildContextThroughBoundedWorkspaceScan() throws Exception {
        Path projectRoot = tempDirectory.resolve("project");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.createDirectories(projectRoot.resolve("node_modules"));
        Files.writeString(projectRoot.resolve("src/App.vue"),
                "<template><main>safe-context</main></template>", StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve("node_modules/secret.ts"),
                "must-not-leak", StandardCharsets.UTF_8);

        GeneratedProjectContextService service = service(projectRoot, new GenerationProjectContextProperties());
        String context = service.build(app(CodeGenTypeEnum.VUE_PROJECT));

        assertTrue(context.contains("src/App.vue"));
        assertTrue(context.contains("safe-context"));
        assertFalse(context.contains("node_modules"));
        assertFalse(context.contains("must-not-leak"));
    }

    @Test
    void shouldExcludeEnvironmentTemplateFromModelContext() throws Exception {
        Path projectRoot = tempDirectory.resolve("sensitive-project");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/App.vue"),
                "<template><main>public-context</main></template>", StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve(".env.example"),
                "DATABASE_PASSWORD=example-secret", StandardCharsets.UTF_8);

        GeneratedProjectContextService service = service(projectRoot, new GenerationProjectContextProperties());
        String context = service.build(app(CodeGenTypeEnum.VUE_PROJECT));

        assertTrue(context.contains("public-context"));
        assertFalse(context.contains(".env.example"));
        assertFalse(context.contains("example-secret"));
    }

    @Test
    void shouldEnforceSingleFileAndTotalContextBudgetsWithoutBreakingCodeFence() throws Exception {
        Path projectRoot = tempDirectory.resolve("budgeted-project");
        Files.createDirectories(projectRoot.resolve("src"));
        String content = String.valueOf((char) 96).repeat(8) + "x".repeat(4_000);
        Files.writeString(projectRoot.resolve("src/App.vue"), content, StandardCharsets.UTF_8);

        GenerationProjectContextProperties properties = new GenerationProjectContextProperties();
        properties.setMaxSingleFileChars(1_024);
        properties.setMaxTotalContextChars(1_100);
        GeneratedProjectContextService service = service(projectRoot, properties);

        String context = service.build(app(CodeGenTypeEnum.VUE_PROJECT));

        assertTrue(context.length() <= 1_100);
        assertTrue(context.contains("文件内容已按上下文预算截断"));
        assertTrue(context.contains("src/App.vue"));
        assertTrue(context.endsWith("~~~"));
    }

    @Test
    void shouldSkipKeyFileThatExceedsBoundedReadLimit() throws Exception {
        Path projectRoot = tempDirectory.resolve("oversized-project");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("index.html"), "x".repeat(2_048), StandardCharsets.UTF_8);

        GenerationProjectContextProperties properties = new GenerationProjectContextProperties();
        properties.setMaxReadableFileBytes(1_024);
        GeneratedProjectContextService service = service(projectRoot, properties);

        String context = service.build(app(CodeGenTypeEnum.HTML));

        assertTrue(context.contains("项目索引"));
        assertTrue(context.contains("index.html"));
        assertFalse(context.contains("当前文件:"));
    }

    @Test
    void shouldReturnEmptyContextWhenWorkspaceDoesNotExist() {
        Path missingRoot = tempDirectory.resolve("missing");
        GeneratedProjectContextService service = service(missingRoot, new GenerationProjectContextProperties());

        assertEquals("", service.build(app(CodeGenTypeEnum.VUE_PROJECT)));
    }

    private GeneratedProjectContextService service(Path projectRoot,
                                                   GenerationProjectContextProperties contextProperties) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        when(workspaceService.resolve(APP_ID, CodeGenTypeEnum.VUE_PROJECT)).thenReturn(
                workspace(normalizedRoot, CodeGenTypeEnum.VUE_PROJECT)
        );
        when(workspaceService.resolve(APP_ID, CodeGenTypeEnum.HTML)).thenReturn(
                workspace(normalizedRoot, CodeGenTypeEnum.HTML)
        );
        return new GeneratedProjectContextService(
                workspaceService,
                new WorkspaceFileSystemService(new WorkspaceFileSystemProperties()),
                contextProperties
        );
    }

    private GenerationWorkspace workspace(Path root, CodeGenTypeEnum codeGenType) {
        return new GenerationWorkspace(
                APP_ID,
                codeGenType,
                root,
                root,
                Files.isDirectory(root),
                root,
                codeGenType == CodeGenTypeEnum.BACKEND_PROJECT ? root : null,
                GenerationWorkspaceService.HIDDEN_FILE_NAMES,
                GenerationWorkspaceService.EDITABLE_EXTENSIONS
        );
    }

    private App app(CodeGenTypeEnum codeGenType) {
        return App.builder().id(APP_ID).codeGenType(codeGenType.getValue()).build();
    }
}
