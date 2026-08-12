package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspaceService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationProvisionalPreviewLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GenerationTaskFinalizerTest {

    private static final GenerationExecutionFence FENCE =
            new GenerationExecutionFence("task-1", "worker-a", 3L);

    private GenerationTaskFinalizationTransaction transaction;
    private GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private GenerationTaskFinalizer finalizer;

    @BeforeEach
    void setUp() {
        transaction = mock(GenerationTaskFinalizationTransaction.class);
        runtimeLifecycleService = mock(GenerationTaskRuntimeLifecycleService.class);
        finalizer = new GenerationTaskFinalizer(transaction, runtimeLifecycleService);
    }

    @Test
    void managedCompletionMustReleaseLeaseOnlyAfterTerminalCommit() {
        GenerationFinalizationCommand command = command();

        finalizer.finalizeManaged(command);

        InOrder order = inOrder(transaction, runtimeLifecycleService);
        order.verify(transaction).finalizeManaged(command);
        order.verify(runtimeLifecycleService).recordTerminalCommit(
                "task-1", GenerationTaskStatus.SUCCESS);
        order.verify(runtimeLifecycleService).releaseTerminalOwnership(FENCE);
    }

    @Test
    void transactionFailureMustKeepLeaseForRecovery() {
        GenerationFinalizationCommand command = command();
        doThrow(new IllegalStateException("数据库不可用"))
                .when(transaction).finalizeManaged(command);

        assertThrows(IllegalStateException.class, () -> finalizer.finalizeManaged(command));

        verify(runtimeLifecycleService, never()).recordTerminalCommit(
                "task-1", GenerationTaskStatus.SUCCESS);
        verify(runtimeLifecycleService, never()).releaseTerminalOwnership(FENCE);
    }

    @Test
    void metricFailureAfterCommitMustStillReleaseLease() {
        GenerationFinalizationCommand command = command();
        doThrow(new IllegalStateException("指标服务不可用"))
                .when(runtimeLifecycleService)
                .recordTerminalCommit("task-1", GenerationTaskStatus.SUCCESS);

        assertDoesNotThrow(() -> finalizer.finalizeManaged(command));

        verify(transaction).finalizeManaged(command);
        verify(runtimeLifecycleService).releaseTerminalOwnership(FENCE);
    }

    @Test
    void failedTaskMustQuarantineWorkspaceAfterTerminalCommit() {
        GenerationExecutionWorkspaceService workspaceService = mock(GenerationExecutionWorkspaceService.class);
        GenerationProvisionalPreviewLifecycle previewLifecycle = mock(GenerationProvisionalPreviewLifecycle.class);
        finalizer = new GenerationTaskFinalizer(
                transaction, runtimeLifecycleService, workspaceService, previewLifecycle);
        GenerationFinalizationCommand command = GenerationFinalizationCommand.of(
                "task-1", 11L, FENCE, GenerationTaskStatus.FAILED,
                "generation_failed", null, null);

        finalizer.finalizeManaged(command);

        verify(previewLifecycle).stopForTerminal(11L, FENCE);
        verify(workspaceService).clear(
                FENCE, 11L, GenerationExecutionWorkspaceService.CleanupPolicy.QUARANTINE);
    }

    private GenerationFinalizationCommand command() {
        return GenerationFinalizationCommand.of(
                "task-1", 11L, FENCE, GenerationTaskStatus.SUCCESS,
                null, "任务状态：成功", null);
    }
}
