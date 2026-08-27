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
    void missingImmutableSnapshotMustNotProduceDiffEvidence() {
        Path tempDir = cleanTestRoot("cross-app");
        Path codeOutputRoot = tempDir.resolve("code_output");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        Path currentProject = codeOutputRoot.resolve("vue_project_11");
        GenerationArtifact rollbackPoint = RollbackPoint.created(
                11L,
                "task-11",
                "missing",
                "11111111-1111-1111-1111-111111111111",
                "a".repeat(64),
                ".",
                1L,
                codeSnapshotRoot.resolve("11/11111111-1111-1111-1111-111111111111").toString(),
                currentProject.toString(),
                "vue_project",
                "vue_project",
                1
        ).toArtifact();

        DiffSummary summary = SnapshotServiceTestFixture.diffSummaryService(codeOutputRoot, codeSnapshotRoot).summarize(11L, CodeGenTypeEnum.VUE_PROJECT, "task-11", rollbackPoint);

        assertEquals("skipped", summary.status());
        assertEquals("rollback_snapshot_validation_failed", summary.reason());
    }

    @Test
    void legacyRollbackPointMustNotProduceDiffEvidence() {
        Path tempDir = cleanTestRoot("snapshot-root");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        GenerationArtifact current = RollbackPoint.created(
                11L, "task-11", "legacy", "11111111-1111-1111-1111-111111111111",
                "a".repeat(64), ".", 1L, "/legacy/snapshot", "/legacy/project",
                "vue_project", "vue_project", 1).toArtifact();
        Map<String, Object> payload = new java.util.LinkedHashMap<>(current.payload());
        payload.put("schemaVersion", RollbackPoint.LEGACY_SCHEMA_VERSION);
        payload.keySet().removeAll(java.util.Set.of(
                "snapshotId", "manifestSha256", "scope", "executionEpoch"));
        GenerationArtifact rollbackPoint = GenerationArtifact.of(
                RollbackPoint.KEY, "Orchestrator", "legacy", payload);

        DiffSummary summary = SnapshotServiceTestFixture.diffSummaryService(tempDir.resolve("code_output"), codeSnapshotRoot).summarize(11L, CodeGenTypeEnum.VUE_PROJECT, "task-11", rollbackPoint);

        assertEquals("rollback_snapshot_identity_unsupported", summary.reason());
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
        try {
            Path codeSnapshotRoot = snapshotRoot.getParent().getParent();
            Path codeOutputRoot = projectRoot.getParent();
            Path source = codeSnapshotRoot.getParent().resolve("diff-fixtures")
                    .resolve(appId + "-" + taskId);
            Files.createDirectories(source.getParent());
            Files.move(snapshotRoot, source);
            SnapshotServiceTestFixture.Components components =
                    SnapshotServiceTestFixture.components(codeOutputRoot, codeSnapshotRoot);
            StoredSnapshot snapshot = components.snapshotWorkspaceService().captureOrReuse(
                    new SnapshotCapture(
                            snapshotRoot.getFileName().toString(),
                            new SnapshotScope(appId, CodeGenTypeEnum.VUE_PROJECT, "."),
                            source,
                            SnapshotKind.ROLLBACK_POINT,
                            taskId,
                            1L
                    ),
                    () -> {
                    }
            );
            return RollbackPoint.created(
                    appId,
                    taskId,
                    snapshot.snapshotName(),
                    snapshot.snapshotId(),
                    snapshot.manifestSha256(),
                    snapshot.scope().relativePath(),
                    snapshot.creatorExecutionEpoch(),
                    snapshot.containerPath().toString(),
                    projectRoot.toString(),
                    CodeGenTypeEnum.VUE_PROJECT.getValue(),
                    CodeGenTypeEnum.VUE_PROJECT.getValue(),
                    snapshot.fingerprint().fileCount()
            ).toArtifact();
        } catch (Exception exception) {
            throw new IllegalStateException("failed to prepare rollback point fixture", exception);
        }
    }
}
