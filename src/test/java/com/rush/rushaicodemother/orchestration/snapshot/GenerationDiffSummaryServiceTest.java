package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.RollbackPoint;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationDiffSummaryServiceTest {

    @Test
    void shouldSummarizeAddedModifiedAndDeletedFiles() throws Exception {
        Path tempDir = cleanTestRoot("changes");
        Path codeOutputRoot = tempDir.resolve("code_output");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        Path snapshotRoot = codeSnapshotRoot.resolve("9").resolve("pre_generation_task-9");
        Path currentRoot = codeOutputRoot.resolve("vue_project_9");
        Files.createDirectories(snapshotRoot.resolve("src"));
        Files.writeString(snapshotRoot.resolve("src/App.vue"), "<template>old</template>\n");
        Files.writeString(snapshotRoot.resolve("src/Removed.vue"), "removed\n");
        Files.createDirectories(snapshotRoot.resolve("node_modules/pkg"));
        Files.writeString(snapshotRoot.resolve("node_modules/pkg/index.js"), "ignored\n");
        Files.createDirectories(currentRoot.resolve("src"));
        Files.writeString(currentRoot.resolve("src/App.vue"), "<template>new</template>\n");
        Files.writeString(currentRoot.resolve("src/Added.vue"), "added\n");
        Files.createDirectories(currentRoot.resolve("node_modules/pkg"));
        Files.writeString(currentRoot.resolve("node_modules/pkg/index.js"), "ignored changed\n");
        GenerationArtifact rollbackPoint = rollbackPoint(9L, "task-9", snapshotRoot, currentRoot);

        DiffSummary summary = SnapshotServiceTestFixture.diffSummaryService(codeOutputRoot, codeSnapshotRoot)
                .summarize(9L, CodeGenTypeEnum.VUE_PROJECT, "task-9", rollbackPoint);

        assertEquals("created", summary.status());
        assertEquals(1, summary.addedCount());
        assertEquals(1, summary.modifiedCount());
        assertEquals(1, summary.deletedCount());
        assertTrue(summary.addedFiles().contains("src/Added.vue"));
        assertTrue(summary.modifiedFiles().contains("src/App.vue"));
        assertTrue(summary.deletedFiles().contains("src/Removed.vue"));
        assertFalse(summary.modifiedFiles().contains("node_modules/pkg/index.js"));
        assertTrue(summary.modifiedDetails().getFirst().contains("src/App.vue"));
    }

    @Test
    void shouldSkipWhenRollbackPointWasNotCreated() {
        Path tempDir = cleanTestRoot("skipped");
        GenerationArtifact rollbackPoint = RollbackPoint.skipped(
                10L,
                "task-10",
                "",
                "vue_project",
                "vue_project",
                "no_existing_generated_code"
        ).toArtifact();

        DiffSummary summary = SnapshotServiceTestFixture.diffSummaryService(
                tempDir.resolve("code_output"),
                tempDir.resolve("code_snapshot")
        )
                .summarize(10L, CodeGenTypeEnum.VUE_PROJECT, "task-10", rollbackPoint);

        assertEquals("skipped", summary.status());
        assertEquals("rollback_point_not_created", summary.reason());
    }

    @Test
    void corruptedRollbackPointMustNotProduceDiffEvidence() {
        Path tempDir = cleanTestRoot("corrupted-artifact");
        GenerationArtifact corrupted = GenerationArtifact.of(
                RollbackPoint.KEY,
                "test",
                "rollback",
                Map.of("status", "created")
        );

        DiffSummary summary = SnapshotServiceTestFixture.diffSummaryService(
                        tempDir.resolve("code_output"),
                        tempDir.resolve("code_snapshot")
                )
                .summarize(10L, CodeGenTypeEnum.VUE_PROJECT, "task-10", corrupted);

        assertEquals("skipped", summary.status());
        assertEquals("rollback_artifact_invalid", summary.reason());
        assertEquals(0, summary.changedFileCount());
    }

    @Test
    void shouldRejectSnapshotOutsideCurrentApplicationBoundary() {
        Path tempDir = cleanTestRoot("cross-app");
        Path codeOutputRoot = tempDir.resolve("code_output");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        Path anotherApplicationSnapshot = codeSnapshotRoot.resolve("12").resolve("pre_generation_task-12");
        GenerationArtifact rollbackPoint = rollbackPoint(
                11L,
                "task-11",
                anotherApplicationSnapshot,
                codeOutputRoot.resolve("vue_project_11")
        );

        DiffSummary summary = SnapshotServiceTestFixture.diffSummaryService(codeOutputRoot, codeSnapshotRoot).summarize(11L, CodeGenTypeEnum.VUE_PROJECT, "task-11", rollbackPoint);

        assertEquals("skipped", summary.status());
        assertEquals("rollback_path_out_of_root", summary.reason());
    }

    @Test
    void shouldRejectApplicationSnapshotRootAsSnapshotPath() {
        Path tempDir = cleanTestRoot("snapshot-root");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        Path applicationSnapshotRoot = codeSnapshotRoot.resolve("11");
        GenerationArtifact rollbackPoint = rollbackPoint(
                11L,
                "task-11",
                applicationSnapshotRoot,
                tempDir.resolve("code_output").resolve("vue_project_11")
        );

        DiffSummary summary = SnapshotServiceTestFixture.diffSummaryService(tempDir.resolve("code_output"), codeSnapshotRoot).summarize(11L, CodeGenTypeEnum.VUE_PROJECT, "task-11", rollbackPoint);

        assertEquals("rollback_path_out_of_root", summary.reason());
    }

    private Path cleanTestRoot(String caseName) {
        Path root = Path.of("target", "test-workspaces", "diff-summary-service", caseName);
        FileUtil.del(root.toFile());
        return root;
    }

    private GenerationArtifact rollbackPoint(Long appId,
                                             String taskId,
                                             Path snapshotRoot,
                                             Path projectRoot) {
        return RollbackPoint.created(
                appId,
                taskId,
                snapshotRoot.getFileName().toString(),
                snapshotRoot.toString(),
                projectRoot.toString(),
                CodeGenTypeEnum.VUE_PROJECT.getValue(),
                CodeGenTypeEnum.VUE_PROJECT.getValue(),
                1
        ).toArtifact();
    }
}
