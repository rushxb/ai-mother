package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.io.FileUtil;
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
        Path snapshotRoot = tempDir.resolve("snapshot");
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
                "snapshotPath", snapshotRoot.toString()
        ));

        DiffSummary summary = new GenerationDiffSummaryService(codeOutputRoot)
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

        DiffSummary summary = new GenerationDiffSummaryService(tempDir)
                .summarize(10L, CodeGenTypeEnum.VUE_PROJECT, "task-10", rollbackPoint);

        assertEquals("skipped", summary.status());
        assertEquals("rollback_point_not_created", summary.reason());
    }

    private Path cleanTestRoot(String caseName) {
        Path root = Path.of("target", "test-workspaces", "diff-summary-service", caseName);
        FileUtil.del(root.toFile());
        return root;
    }
}
