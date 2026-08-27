package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.rush.rushaicodemother.orchestration.GenerationOrchestrationTestFixture.frozenRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
        GenerationOrchestrationRequest request = frozenRequest(
                app, "修改页面", CodeGenTypeEnum.VUE_PROJECT, "update", true);

        GenerationTaskFenceGuard fenceGuard = mock(GenerationTaskFenceGuard.class);
        GenerationArtifact artifact = SnapshotServiceTestFixture.rollbackPointService(
                        codeOutputRoot, codeSnapshotRoot, fenceGuard)
                .prepareRollbackPoint(request, CodeGenTypeEnum.VUE_PROJECT, "task-7");

        assertEquals("rollback_point", artifact.key());
        assertEquals("created", artifact.payload().get("status"));
        assertEquals(1, artifact.payload().get("fileCount"));
        Path snapshotPath = Path.of(String.valueOf(artifact.payload().get("snapshotPath")));
        assertTrue(Files.exists(snapshotPath.resolve("payload/src/App.vue")));
        assertFalse(Files.exists(snapshotPath.resolve("payload/node_modules/pkg/index.js")));
        verify(fenceGuard).assertCurrent("task-7");
    }

    @Test
    void shouldSkipWhenThereIsNoExistingGeneratedCode() {
        Path tempDir = cleanTestRoot("skip");
        App app = new App();
        app.setId(8L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        GenerationOrchestrationRequest request = frozenRequest(
                app, "创建页面", CodeGenTypeEnum.HTML, "create", false);

        GenerationArtifact artifact = SnapshotServiceTestFixture.rollbackPointService(tempDir.resolve("out"), tempDir.resolve("snap"))
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
        GenerationOrchestrationRequest request = frozenRequest(
                app, "修改页面", CodeGenTypeEnum.HTML, "update", true);

        GenerationArtifact artifact = SnapshotServiceTestFixture.rollbackPointService(codeOutputRoot, codeSnapshotRoot)
                .prepareRollbackPoint(
                        request,
                        CodeGenTypeEnum.HTML,
                        "very-long-task-id-".repeat(6) + "task"
                );

        String snapshotName = String.valueOf(artifact.payload().get("snapshotName"));
        assertEquals("created", artifact.payload().get("status"));
        assertTrue(snapshotName.length() <= 128);
        assertTrue(snapshotName.matches("[A-Za-z0-9_-]+"));
        assertFalse(snapshotName.contains(".."));
    }

    @Test
    void repeatedPreparationMustReuseTheTaskScopedSnapshot() throws Exception {
        Path tempDir = cleanTestRoot("idempotent");
        Path codeOutputRoot = tempDir.resolve("code_output");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        Path projectRoot = codeOutputRoot.resolve("html_19");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("index.html"), "ok");
        App app = new App();
        app.setId(19L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        GenerationOrchestrationRequest request = frozenRequest(
                app, "修改页面", CodeGenTypeEnum.HTML, "update", true);
        GenerationTaskFenceGuard fenceGuard = mock(GenerationTaskFenceGuard.class);
        GenerationRollbackPointService service = SnapshotServiceTestFixture.rollbackPointService(
                codeOutputRoot, codeSnapshotRoot, fenceGuard);

        GenerationArtifact first = service.prepareRollbackPoint(request, CodeGenTypeEnum.HTML, "task-19");
        GenerationArtifact resumed = service.prepareRollbackPoint(request, CodeGenTypeEnum.HTML, "task-19");

        assertEquals("created", first.payload().get("status"));
        assertEquals(first.payload().get("snapshotName"), resumed.payload().get("snapshotName"));
        assertEquals(first.payload().get("snapshotPath"), resumed.payload().get("snapshotPath"));
        assertEquals(first.payload().get("fileCount"), resumed.payload().get("fileCount"));
        try (var snapshots = Files.list(codeSnapshotRoot.resolve("19"))) {
            assertEquals(1L, snapshots.count());
        }
        verify(fenceGuard, times(2)).assertCurrent("task-19");
    }

    private Path cleanTestRoot(String caseName) {
        Path root = Path.of("target", "test-workspaces", "rollback-service", caseName);
        FileUtil.del(root.toFile());
        return root;
    }
}
