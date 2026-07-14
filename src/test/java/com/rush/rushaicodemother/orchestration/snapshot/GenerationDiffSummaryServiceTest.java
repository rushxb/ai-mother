package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemTestFactory;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
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
        GenerationArtifact rollbackPoint = GenerationArtifact.of("rollback_point", "test", "rollback", Map.of(
                "status", "created",
                "appId", 9L,
                "taskId", "task-9",
                "snapshotName", snapshotRoot.getFileName().toString(),
                "snapshotPath", snapshotRoot.toString()
        ));

        DiffSummary summary = new GenerationDiffSummaryService(
                codeOutputRoot,
                codeSnapshotRoot,
                WorkspaceFileSystemTestFactory.create()
        )
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
        GenerationArtifact rollbackPoint = GenerationArtifact.of("rollback_point", "test", "rollback", Map.of(
                "status", "skipped",
                "reason", "no_existing_generated_code"
        ));

        DiffSummary summary = new GenerationDiffSummaryService(
                tempDir,
                tempDir.resolve("snapshots"),
                WorkspaceFileSystemTestFactory.create()
        )
                .summarize(10L, CodeGenTypeEnum.VUE_PROJECT, "task-10", rollbackPoint);

        assertEquals("skipped", summary.status());
        assertEquals("rollback_point_not_created", summary.reason());
    }

    @Test
    void shouldRejectSnapshotOutsideCurrentApplicationBoundary() {
        Path tempDir = cleanTestRoot("cross-app");
        Path codeOutputRoot = tempDir.resolve("code_output");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        Path anotherApplicationSnapshot = codeSnapshotRoot.resolve("12").resolve("pre_generation_task-12");
        GenerationArtifact rollbackPoint = GenerationArtifact.of("rollback_point", "test", "rollback", Map.of(
                "status", "created",
                "appId", 11L,
                "taskId", "task-11",
                "snapshotName", anotherApplicationSnapshot.getFileName().toString(),
                "snapshotPath", anotherApplicationSnapshot.toString()
        ));

        DiffSummary summary = new GenerationDiffSummaryService(
                codeOutputRoot,
                codeSnapshotRoot,
                WorkspaceFileSystemTestFactory.create()
        ).summarize(11L, CodeGenTypeEnum.VUE_PROJECT, "task-11", rollbackPoint);

        assertEquals("skipped", summary.status());
        assertEquals("rollback_path_out_of_root", summary.reason());
    }

    @Test
    void shouldRejectApplicationSnapshotRootAsSnapshotPath() {
        Path tempDir = cleanTestRoot("snapshot-root");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        Path applicationSnapshotRoot = codeSnapshotRoot.resolve("11");
        GenerationArtifact rollbackPoint = GenerationArtifact.of("rollback_point", "test", "rollback", Map.of(
                "status", "created",
                "appId", 11L,
                "taskId", "task-11",
                "snapshotPath", applicationSnapshotRoot.toString()
        ));

        DiffSummary summary = new GenerationDiffSummaryService(
                tempDir.resolve("code_output"),
                codeSnapshotRoot,
                WorkspaceFileSystemTestFactory.create()
        ).summarize(11L, CodeGenTypeEnum.VUE_PROJECT, "task-11", rollbackPoint);

        assertEquals("rollback_path_out_of_root", summary.reason());
    }

    private Path cleanTestRoot(String caseName) {
        Path root = Path.of("target", "test-workspaces", "diff-summary-service", caseName);
        FileUtil.del(root.toFile());
        return root;
    }
}
