package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemTestFactory;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRollbackPointServiceTest {

    @Test
    void shouldCreateLocalSnapshotForExistingGeneratedCode() throws Exception {
        Path tempDir = cleanTestRoot("create");
        Path codeOutputRoot = tempDir.resolve("code_output");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        Path projectRoot = codeOutputRoot.resolve("vue_project_7");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/App.vue"), "<template>ok</template>");
        Files.createDirectories(projectRoot.resolve("node_modules/pkg"));
        Files.writeString(projectRoot.resolve("node_modules/pkg/index.js"), "ignored");

        App app = new App();
        app.setId(7L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "修改页面",
                CodeGenTypeEnum.VUE_PROJECT,
                "update",
                true,
                null,
                null,
                null
        );

        GenerationArtifact artifact = new GenerationRollbackPointService(
                codeOutputRoot,
                codeSnapshotRoot,
                WorkspaceFileSystemTestFactory.create()
        )
                .prepareRollbackPoint(request, CodeGenTypeEnum.VUE_PROJECT, "task-7");

        assertEquals("rollback_point", artifact.key());
        assertEquals("created", artifact.payload().get("status"));
        assertEquals(1, artifact.payload().get("fileCount"));
        Path snapshotPath = Path.of(String.valueOf(artifact.payload().get("snapshotPath")));
        assertTrue(Files.exists(snapshotPath.resolve("src/App.vue")));
        assertFalse(Files.exists(snapshotPath.resolve("node_modules/pkg/index.js")));
    }

    @Test
    void shouldSkipWhenThereIsNoExistingGeneratedCode() {
        Path tempDir = cleanTestRoot("skip");
        App app = new App();
        app.setId(8L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "创建页面",
                CodeGenTypeEnum.HTML,
                "create",
                false,
                null,
                null,
                null
        );

        GenerationArtifact artifact = new GenerationRollbackPointService(
                tempDir.resolve("out"),
                tempDir.resolve("snap"),
                WorkspaceFileSystemTestFactory.create()
        )
                .prepareRollbackPoint(request, CodeGenTypeEnum.HTML, "task-8");

        assertEquals("skipped", artifact.payload().get("status"));
        assertEquals("no_existing_generated_code", artifact.payload().get("reason"));
    }

    @Test
    void shouldBoundAndSanitizeTaskIdUsedInSnapshotDirectoryName() throws Exception {
        Path tempDir = cleanTestRoot("bounded-task-id");
        Path codeOutputRoot = tempDir.resolve("code_output");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        Path projectRoot = codeOutputRoot.resolve("html_18");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("index.html"), "ok");
        App app = new App();
        app.setId(18L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app, "修改页面", CodeGenTypeEnum.HTML, "update", true, null, null, null
        );

        GenerationArtifact artifact = new GenerationRollbackPointService(
                codeOutputRoot,
                codeSnapshotRoot,
                WorkspaceFileSystemTestFactory.create()
        ).prepareRollbackPoint(request, CodeGenTypeEnum.HTML, "../" + "very-long-task-id".repeat(20));

        String snapshotName = String.valueOf(artifact.payload().get("snapshotName"));
        assertEquals("created", artifact.payload().get("status"));
        assertTrue(snapshotName.length() <= 128);
        assertTrue(snapshotName.matches("[A-Za-z0-9_-]+"));
        assertFalse(snapshotName.contains(".."));
    }

    private Path cleanTestRoot(String caseName) {
        Path root = Path.of("target", "test-workspaces", "rollback-service", caseName);
        FileUtil.del(root.toFile());
        return root;
    }
}
