package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationRollbackRestoreService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HeavyGenerationFailureRecoveryServiceTest {

    @TempDir
    Path workspaceRoot;

    private GenerationAppStateService appStateService;
    private GenerationWorkspaceService workspaceService;
    private GenerationTaskFenceGuard fenceGuard;
    private HeavyGenerationFailureRecoveryService service;

    @BeforeEach
    void setUp() {
        appStateService = mock(GenerationAppStateService.class);
        workspaceService = mock(GenerationWorkspaceService.class);
        fenceGuard = mock(GenerationTaskFenceGuard.class);
        service = new HeavyGenerationFailureRecoveryService(
                appStateService,
                mock(GenerationOrchestrationMetricsCollector.class),
                mock(GenerationRollbackRestoreService.class),
                workspaceService,
                fenceGuard);
    }

    @Test
    void staleAppOwnershipMustBeRejectedBeforeTheUpgradedWorkspaceIsDeleted() throws Exception {
        Files.writeString(workspaceRoot.resolve("keep.txt"), "keep");
        GenerationExecutionPolicyException stale =
                new GenerationExecutionPolicyException("stale execution");
        doThrow(stale).when(appStateService).updateOwnedCodeGenType(
                1L, "task-1", CodeGenTypeEnum.HTML);

        assertThrows(GenerationExecutionPolicyException.class,
                () -> service.rollbackCodeGenTypeIfNeeded(1L, preparation()));

        assertTrue(Files.exists(workspaceRoot.resolve("keep.txt")));
        verify(workspaceService, never()).resolve(1L, CodeGenTypeEnum.VUE_PROJECT);
        verify(fenceGuard, never()).assertCurrent("task-1");
    }

    @Test
    void failedUpgradeWorkspaceMustBeQuarantinedInsteadOfDeletedByMutablePathLookup() throws Exception {
        Files.writeString(workspaceRoot.resolve("keep.txt"), "keep");

        service.rollbackCodeGenTypeIfNeeded(1L, preparation());

        verify(appStateService).updateOwnedCodeGenType(
                1L, "task-1", CodeGenTypeEnum.HTML);
        verify(workspaceService, never()).resolve(1L, CodeGenTypeEnum.VUE_PROJECT);
        verify(fenceGuard, never()).assertCurrent("task-1");
        assertTrue(Files.exists(workspaceRoot.resolve("keep.txt")));
    }

    private GenerationPreparation preparation() {
        return new GenerationPreparation(
                CodeGenTypeEnum.HTML,
                CodeGenTypeEnum.VUE_PROJECT,
                true,
                "build",
                "prompt",
                List.of(),
                new LinkedHashMap<>(),
                null,
                Map.of(),
                "task-1");
    }
}
