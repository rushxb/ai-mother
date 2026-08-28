package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.testing.GenerationFailureMatrix;
import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemException;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceDirectoryFingerprint;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.RollbackPoint;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

@Tag(GenerationFailureMatrix.TAG)
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
        assertTrue(Files.exists(backupPath.resolve("payload/src/App.vue")));
        assertTrue(Files.exists(backupPath.resolve("payload/src/New.vue")));
        assertFalse(Files.exists(backupPath.resolve("payload/node_modules/pkg/index.js")));
        verify(fenceGuard, atLeast(2)).assertCurrent("task-11");
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
            if (postBackupFenceChecks.incrementAndGet() == 2) {
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
        assertEquals(2, postBackupFenceChecks.get());
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
        GenerationArtifact rollbackArtifact = rollbackPoint(
                22L, "task-22", "vue_project", snapshotRoot, projectRoot);
        RollbackPoint point = RollbackPoint.fromArtifact(rollbackArtifact, 22L, "task-22");
        Path sourceContainer = Path.of(point.snapshotPath());
        StoredSnapshot sourceSnapshot = new StoredSnapshot(
                point.snapshotName(), point.snapshotId(),
                new SnapshotScope(22L, CodeGenTypeEnum.VUE_PROJECT, point.scope()),
                SnapshotKind.ROLLBACK_POINT, point.taskId(), point.executionEpoch(),
                sourceContainer, sourceContainer.resolve("payload"),
                new WorkspaceDirectoryFingerprint(1, 15, "a".repeat(64)),
                point.manifestSha256(), Instant.parse("2026-08-27T10:00:00Z")
        );
        StoredSnapshot backupSnapshot = new StoredSnapshot(
                "backup", "22222222-2222-2222-2222-222222222222",
                sourceSnapshot.scope(), SnapshotKind.FAILED_GENERATION_BACKUP,
                point.taskId(), point.executionEpoch(),
                codeSnapshotRoot.resolve("22/22222222-2222-2222-2222-222222222222"),
                codeSnapshotRoot.resolve("22/22222222-2222-2222-2222-222222222222/payload"),
                new WorkspaceDirectoryFingerprint(1, 15, "b".repeat(64)),
                "c".repeat(64), Instant.parse("2026-08-27T10:01:00Z")
        );
        GenerationSnapshotWorkspaceService snapshotService = mock(GenerationSnapshotWorkspaceService.class);
        when(snapshotService.requireSnapshot(any())).thenReturn(sourceSnapshot);
        when(snapshotService.captureOrReuse(any(), any())).thenReturn(backupSnapshot);
        when(snapshotService.restore(any(), any(), any())).thenThrow(new WorkspaceFileSystemException(
                WorkspaceFileSystemException.Reason.REPLACE_OUTCOME_UNKNOWN,
                "physical outcome unknown"
        ));
        WorkspaceFileSystemService fileSystemService = mock(WorkspaceFileSystemService.class);
        when(fileSystemService.isDirectory(any(Path.class))).thenReturn(true);
        GenerationExecutionContextService executionContextService = mock(GenerationExecutionContextService.class);
        when(executionContextService.getExecutionFence("task-22")).thenReturn(Optional.of(
                new GenerationExecutionFence("task-22", "worker", 1L)
        ));
        GenerationRollbackRestoreService service = new GenerationRollbackRestoreService(
                components.generationWorkspaceService(),
                snapshotService,
                fileSystemService,
                components.snapshotNamePolicy(),
                mock(GenerationTaskFenceGuard.class),
                executionContextService
        );

        GenerationArtifact artifact = service.restoreIfAllowed(
                22L,
                "task-22",
                snapshotChangePlan(),
                rollbackArtifact
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
    void legacyRollbackPointMustNeverTriggerAutomaticRestore() {
        Path tempDir = cleanTestRoot("legacy-artifact");
        RollbackPoint current = RollbackPoint.created(
                13L, "task-13", "legacy", "11111111-1111-1111-1111-111111111111",
                "a".repeat(64), ".", 1L, "/legacy/snapshot", "/legacy/project",
                "html", "html", 1);
        Map<String, Object> payload = new java.util.LinkedHashMap<>(current.toPayload());
        payload.put("schemaVersion", RollbackPoint.LEGACY_SCHEMA_VERSION);
        payload.keySet().removeAll(java.util.Set.of(
                "snapshotId", "manifestSha256", "scope", "executionEpoch"));
        GenerationArtifact legacy = GenerationArtifact.of(
                RollbackPoint.KEY, "Orchestrator", "legacy", payload);

        GenerationArtifact result = SnapshotServiceTestFixture.rollbackRestoreService(
                        tempDir.resolve("code_output"), tempDir.resolve("code_snapshot"))
                .restoreIfAllowed(13L, "task-13", snapshotChangePlan(), legacy);

        assertEquals("skipped", result.payload().get("status"));
        assertEquals("rollback_snapshot_identity_unsupported", result.payload().get("reason"));
        assertFalse(Files.exists(tempDir.resolve("code_output")));
        assertFalse(Files.exists(tempDir.resolve("code_snapshot")));
    }

    @Test
    void shouldRejectProjectRootArtifactBeforeSnapshotLookup() {
        Path tempDir = cleanTestRoot("root-boundaries");
        Path codeOutputRoot = tempDir.resolve("code_output");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        GenerationRollbackRestoreService service = SnapshotServiceTestFixture.rollbackRestoreService(codeOutputRoot, codeSnapshotRoot);

        GenerationArtifact rollbackPoint = RollbackPoint.created(
                11L, "task-11", "missing", "11111111-1111-1111-1111-111111111111",
                "a".repeat(64), ".", 1L,
                codeSnapshotRoot.resolve("11/11111111-1111-1111-1111-111111111111").toString(),
                codeOutputRoot.toString(), "vue_project", "vue_project", 1).toArtifact();
        GenerationArtifact projectRootResult = service.restoreIfAllowed(
                11L,
                "task-11",
                snapshotChangePlan(),
                rollbackPoint
        );

        assertEquals("rollback_path_out_of_root", projectRootResult.payload().get("reason"));
    }

    @Test
    void shouldRejectRollbackPointOwnedByAnotherApplication() {
        Path tempDir = cleanTestRoot("cross-app");
        Path codeOutputRoot = tempDir.resolve("code_output");
        Path codeSnapshotRoot = tempDir.resolve("code_snapshot");
        GenerationArtifact foreign = RollbackPoint.created(
                12L, "task-11", "foreign", "11111111-1111-1111-1111-111111111111",
                "a".repeat(64), ".", 1L, "/snapshot", "/project",
                "vue_project", "vue_project", 1).toArtifact();
        GenerationArtifact artifact = SnapshotServiceTestFixture.rollbackRestoreService(codeOutputRoot, codeSnapshotRoot).restoreIfAllowed(
                11L,
                "task-11",
                snapshotChangePlan(),
                foreign
        );

        assertEquals("skipped", artifact.payload().get("status"));
        assertEquals("rollback_artifact_invalid", artifact.payload().get("reason"));
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
        try {
            Path codeSnapshotRoot = snapshotRoot.getParent().getParent();
            Path codeOutputRoot = projectRoot.getParent();
            Path source = codeSnapshotRoot.getParent().resolve("rollback-fixtures")
                    .resolve(appId + "-" + taskId);
            Files.createDirectories(source.getParent());
            Files.move(snapshotRoot, source);
            SnapshotServiceTestFixture.Components components =
                    SnapshotServiceTestFixture.components(codeOutputRoot, codeSnapshotRoot);
            CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(sourceType);
            StoredSnapshot snapshot = components.snapshotWorkspaceService().captureOrReuse(
                    new SnapshotCapture(
                            snapshotRoot.getFileName().toString(),
                            new SnapshotScope(appId, type, "."),
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
                    sourceType,
                    sourceType,
                    snapshot.fingerprint().fileCount()
            ).toArtifact();
        } catch (Exception exception) {
            throw new IllegalStateException("failed to prepare rollback point fixture", exception);
        }
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
