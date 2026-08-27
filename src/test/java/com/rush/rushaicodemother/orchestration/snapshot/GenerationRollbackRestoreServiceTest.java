package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemException;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.RollbackPoint;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        verify(fenceGuard, atLeast(3)).assertCurrent("task-11");
    }

    @Test
    void leaseLostDuringRestoreStagingMustAbortAndKeepCurrentProject() throws Exception {
        Path tempDir = cleanTestRoot("lease-lost-during-restore-staging");
        Path codeOutputRoot = tempDir.resolve("code_output");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        Path projectRoot = codeOutputRoot.resolve("vue_project_21");
        Path snapshotRoot = codeSnapshotRoot.resolve("21").resolve("pre_generation_task-21");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/App.vue"), "new-owner-version");
        Files.createDirectories(snapshotRoot.resolve("src"));
        Files.writeString(snapshotRoot.resolve("src/App.vue"), "stale-snapshot");
        GenerationTaskFenceGuard fenceGuard = mock(GenerationTaskFenceGuard.class);
        GenerationExecutionPolicyException leaseLost =
                new GenerationExecutionPolicyException("generation task execution fence is no longer current");
        AtomicInteger postBackupFenceChecks = new AtomicInteger();
        doAnswer(invocation -> {
            if (hasDirectoryStartingWith(
                    codeSnapshotRoot.resolve("21"),
                    "failed_generation_task-21_"
            ) && postBackupFenceChecks.incrementAndGet() == 4) {
                throw leaseLost;
            }
            return null;
        }).when(fenceGuard).assertCurrent("task-21");
        GenerationRollbackRestoreService service = SnapshotServiceTestFixture.rollbackRestoreService(
                codeOutputRoot,
                codeSnapshotRoot,
                fenceGuard
        );

        GenerationExecutionPolicyException thrown = assertThrows(
                GenerationExecutionPolicyException.class,
                () -> service.restoreIfAllowed(
                        21L,
                        "task-21",
                        snapshotChangePlan(),
                        rollbackPoint(21L, "task-21", "vue_project", snapshotRoot, projectRoot)
                )
        );

        assertSame(leaseLost, thrown);
        assertEquals("new-owner-version", Files.readString(projectRoot.resolve("src/App.vue")));
        assertEquals(4, postBackupFenceChecks.get());
        try (java.util.stream.Stream<Path> children = Files.list(projectRoot.getParent())) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString()
                    .startsWith(".vue_project_21.restore-")));
        }
    }

    @Test
    void unknownPhysicalOutcomeMustBePersistedAsDedicatedRollbackFailureReason() throws Exception {
        Path tempDir = cleanTestRoot("unknown-physical-outcome");
        Path codeOutputRoot = tempDir.resolve("code_output");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        Path projectRoot = codeOutputRoot.resolve("vue_project_22");
        Path snapshotRoot = codeSnapshotRoot.resolve("22").resolve("pre_generation_task-22");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/App.vue"), "current-version");
        Files.createDirectories(snapshotRoot.resolve("src"));
        Files.writeString(snapshotRoot.resolve("src/App.vue"), "snapshot-version");
        SnapshotServiceTestFixture.Components components =
                SnapshotServiceTestFixture.components(codeOutputRoot, codeSnapshotRoot);
        WorkspaceFileSystemService fileSystemService = mock(WorkspaceFileSystemService.class);
        when(fileSystemService.isDirectory(any(Path.class))).thenReturn(true);
        when(fileSystemService.copyDirectory(
                any(Path.class), any(Path.class), any(Runnable.class)))
                .thenReturn(new WorkspaceFileSystemService.WorkspaceCopyResult(
                        codeSnapshotRoot.resolve("backup"), 1, 15));
        when(fileSystemService.replaceDirectory(
                any(Path.class), any(Path.class), any(Runnable.class)
        )).thenThrow(new WorkspaceFileSystemException(
                WorkspaceFileSystemException.Reason.REPLACE_OUTCOME_UNKNOWN,
                "physical outcome unknown"
        ));
        GenerationRollbackRestoreService service = new GenerationRollbackRestoreService(
                components.generationWorkspaceService(),
                components.snapshotWorkspaceService(),
                fileSystemService,
                components.snapshotNamePolicy(),
                mock(GenerationTaskFenceGuard.class)
        );

        GenerationArtifact artifact = service.restoreIfAllowed(
                22L,
                "task-22",
                snapshotChangePlan(),
                rollbackPoint(22L, "task-22", "vue_project", snapshotRoot, projectRoot)
        );

        assertEquals("failed", artifact.payload().get("status"));
        assertEquals("rollback_restore_outcome_unknown", artifact.payload().get("reason"));
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
        GenerationArtifact rollbackPoint = RollbackPoint.skipped(
                13L,
                "task-13",
                "",
                "html",
                "html",
                "no_existing_generated_code"
        ).toArtifact();

        GenerationArtifact artifact = SnapshotServiceTestFixture.rollbackRestoreService(tempDir.resolve("code_output"), tempDir.resolve("code_snapshot")).restoreIfAllowed(13L, "task-13", snapshotChangePlan(), rollbackPoint);

        assertEquals("skipped", artifact.payload().get("status"));
        assertEquals("rollback_point_not_created", artifact.payload().get("reason"));
    }

    @Test
    void corruptedRollbackPointMustBeSkippedWithoutTouchingWorkspace() {
        Path tempDir = cleanTestRoot("corrupted-artifact");
        GenerationArtifact corrupted = GenerationArtifact.of(
                RollbackPoint.KEY,
                "test",
                "rollback",
                Map.of("status", "created")
        );

        GenerationArtifact artifact = SnapshotServiceTestFixture.rollbackRestoreService(
                        tempDir.resolve("code_output"),
                        tempDir.resolve("code_snapshot")
                )
                .restoreIfAllowed(13L, "task-13", snapshotChangePlan(), corrupted);

        assertEquals("skipped", artifact.payload().get("status"));
        assertEquals("rollback_artifact_invalid", artifact.payload().get("reason"));
        assertFalse(Files.exists(tempDir.resolve("code_output")));
        assertFalse(Files.exists(tempDir.resolve("code_snapshot")));
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
        return new ChangePlan(
                "v1",
                "component_update",
                List.of(),
                List.of("src/App.vue"),
                List.of(),
                List.of("frontend"),
                "build_validation",
                "rollback_to_last_stable_snapshot_or_manual_retry"
        ).toArtifact("test", "plan");
    }

    private GenerationArtifact manualChangePlan() {
        return new ChangePlan(
                "v1",
                "component_update",
                List.of(),
                List.of("index.html"),
                List.of(),
                List.of("frontend"),
                "review_only",
                "manual_retry_without_snapshot"
        ).toArtifact("test", "plan");
    }

    private GenerationArtifact rollbackPoint(Long appId,
                                             String taskId,
                                             String sourceType,
                                             Path snapshotRoot,
                                             Path projectRoot) {
        return RollbackPoint.created(
                appId,
                taskId,
                snapshotRoot.getFileName().toString(),
                snapshotRoot.toString(),
                projectRoot.toString(),
                sourceType,
                sourceType,
                1
        ).toArtifact();
    }

    private boolean hasDirectoryStartingWith(Path root, String prefix) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (java.util.stream.Stream<Path> children = Files.list(root)) {
            return children.anyMatch(path -> Files.isDirectory(path)
                    && path.getFileName().toString().startsWith(prefix));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed to inspect generated backup", exception);
        }
    }

    private Path cleanTestRoot(String caseName) {
        Path root = Path.of("target", "test-workspaces", "rollback-restore-service", caseName);
        FileUtil.del(root.toFile());
        return root;
    }
}
