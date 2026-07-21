package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GenerationRollbackRestoreServiceTest {

    @Test
    void shouldRestoreSnapshotWhenRollbackStrategyOptedIn() throws Exception {
        Path tempDir = cleanTestRoot("restore");
        Path codeOutputRoot = tempDir.resolve("code_output");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        Path projectRoot = codeOutputRoot.resolve("vue_project_11");
        Path snapshotRoot = codeSnapshotRoot.resolve("11").resolve("pre_generation_task-11");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/App.vue"), "broken");
        Files.writeString(projectRoot.resolve("src/New.vue"), "new");
        Files.createDirectories(projectRoot.resolve("node_modules/pkg"));
        Files.writeString(projectRoot.resolve("node_modules/pkg/index.js"), "ignored broken");
        Files.createDirectories(snapshotRoot.resolve("src"));
        Files.writeString(snapshotRoot.resolve("src/App.vue"), "stable");

        GenerationTaskFenceGuard fenceGuard = mock(GenerationTaskFenceGuard.class);
        GenerationArtifact artifact = SnapshotServiceTestFixture.rollbackRestoreService(
                        codeOutputRoot, codeSnapshotRoot, fenceGuard)
                .restoreIfAllowed(
                        11L,
                        "task-11",
                        snapshotChangePlan(),
                        rollbackPoint(11L, "task-11", "vue_project", snapshotRoot, projectRoot)
                );

        assertEquals("rollback_restore", artifact.key());
        assertEquals("restored", artifact.payload().get("status"));
        assertEquals("stable", Files.readString(projectRoot.resolve("src/App.vue")));
        assertFalse(Files.exists(projectRoot.resolve("src/New.vue")));
        assertFalse(Files.exists(projectRoot.resolve("node_modules/pkg/index.js")));
        Path backupPath = Path.of(String.valueOf(artifact.payload().get("backupPath")));
        assertTrue(Files.exists(backupPath.resolve("src/App.vue")));
        assertTrue(Files.exists(backupPath.resolve("src/New.vue")));
        assertFalse(Files.exists(backupPath.resolve("node_modules/pkg/index.js")));
        verify(fenceGuard).assertCurrent("task-11");
    }

    @Test
    void shouldSkipWhenRollbackStrategyIsManual() throws Exception {
        Path tempDir = cleanTestRoot("manual");
        Path codeOutputRoot = tempDir.resolve("code_output");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        Path projectRoot = codeOutputRoot.resolve("html_12");
        Path snapshotRoot = codeSnapshotRoot.resolve("12").resolve("pre_generation_task-12");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("index.html"), "broken");
        Files.createDirectories(snapshotRoot);
        Files.writeString(snapshotRoot.resolve("index.html"), "stable");

        GenerationArtifact artifact = SnapshotServiceTestFixture.rollbackRestoreService(codeOutputRoot, codeSnapshotRoot)
                .restoreIfAllowed(
                        12L,
                        "task-12",
                        manualChangePlan(),
                        rollbackPoint(12L, "task-12", "html", snapshotRoot, projectRoot)
                );

        assertEquals("skipped", artifact.payload().get("status"));
        assertEquals("rollback_strategy_not_snapshot", artifact.payload().get("reason"));
        assertEquals("broken", Files.readString(projectRoot.resolve("index.html")));
    }

    @Test
    void shouldSkipWhenRollbackPointWasNotCreated() {
        Path tempDir = cleanTestRoot("skipped");
        GenerationArtifact rollbackPoint = GenerationArtifact.of("rollback_point", "test", "rollback", Map.of(
                "status", "skipped",
                "reason", "no_existing_generated_code"
        ));

        GenerationArtifact artifact = SnapshotServiceTestFixture.rollbackRestoreService(tempDir.resolve("code_output"), tempDir.resolve("code_snapshot")).restoreIfAllowed(13L, "task-13", snapshotChangePlan(), rollbackPoint);

        assertEquals("skipped", artifact.payload().get("status"));
        assertEquals("rollback_point_not_created", artifact.payload().get("reason"));
    }

    @Test
    void shouldRejectSnapshotRootAndProjectRootArtifacts() {
        Path tempDir = cleanTestRoot("root-boundaries");
        Path codeOutputRoot = tempDir.resolve("code_output");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        GenerationRollbackRestoreService service = SnapshotServiceTestFixture.rollbackRestoreService(codeOutputRoot, codeSnapshotRoot);

        GenerationArtifact snapshotRootResult = service.restoreIfAllowed(
                11L,
                "task-11",
                snapshotChangePlan(),
                rollbackPoint(11L, "task-11", "vue_project", codeSnapshotRoot, codeOutputRoot.resolve("vue_project_11"))
        );
        GenerationArtifact projectRootResult = service.restoreIfAllowed(
                11L,
                "task-11",
                snapshotChangePlan(),
                rollbackPoint(
                        11L,
                        "task-11",
                        "vue_project",
                        codeSnapshotRoot.resolve("11").resolve("snapshot"),
                        codeOutputRoot
                )
        );

        assertEquals("rollback_path_out_of_root", snapshotRootResult.payload().get("reason"));
        assertEquals("rollback_path_out_of_root", projectRootResult.payload().get("reason"));
    }

    @Test
    void shouldRejectSnapshotOwnedByAnotherApplication() {
        Path tempDir = cleanTestRoot("cross-app");
        Path codeOutputRoot = tempDir.resolve("code_output");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        GenerationArtifact artifact = SnapshotServiceTestFixture.rollbackRestoreService(codeOutputRoot, codeSnapshotRoot).restoreIfAllowed(
                11L,
                "task-11",
                snapshotChangePlan(),
                rollbackPoint(
                        11L,
                        "task-11",
                        "vue_project",
                        codeSnapshotRoot.resolve("12").resolve("snapshot"),
                        codeOutputRoot.resolve("vue_project_11")
                )
        );

        assertEquals("skipped", artifact.payload().get("status"));
        assertEquals("rollback_path_out_of_root", artifact.payload().get("reason"));
    }

    private GenerationArtifact snapshotChangePlan() {
        return GenerationArtifact.of("change_plan", "test", "plan", Map.of(
                "schemaVersion", "v1",
                "changeScope", "component_update",
                "modifyFiles", java.util.List.of("src/App.vue"),
                "validationLevel", "build_validation",
                "rollbackStrategy", "rollback_to_last_stable_snapshot_or_manual_retry"
        ));
    }

    private GenerationArtifact manualChangePlan() {
        return GenerationArtifact.of("change_plan", "test", "plan", Map.of(
                "schemaVersion", "v1",
                "changeScope", "component_update",
                "modifyFiles", java.util.List.of("index.html"),
                "validationLevel", "review_only",
                "rollbackStrategy", "manual_retry_without_snapshot"
        ));
    }

    private GenerationArtifact rollbackPoint(Long appId,
                                             String taskId,
                                             String sourceType,
                                             Path snapshotRoot,
                                             Path projectRoot) {
        return GenerationArtifact.of("rollback_point", "test", "rollback", Map.of(
                "status", "created",
                "appId", appId,
                "taskId", taskId,
                "sourceType", sourceType,
                "snapshotName", snapshotRoot.getFileName().toString(),
                "snapshotPath", snapshotRoot.toString(),
                "projectPath", projectRoot.toString()
        ));
    }

    private Path cleanTestRoot(String caseName) {
        Path root = Path.of("target", "test-workspaces", "rollback-restore-service", caseName);
        FileUtil.del(root.toFile());
        return root;
    }
}
