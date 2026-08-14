package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class GenerationTaskFinalizationTransactionTest {

    private GenerationTaskLifecycleService taskLifecycleService;
    private GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private DurableGenerationTaskRepository taskRepository;
    private GenerationAppStateService appStateService;
    private GenerationTaskFinalizationTransaction transaction;

    @BeforeEach
    void setUp() {
        taskLifecycleService = mock(GenerationTaskLifecycleService.class);
        runtimeLifecycleService = mock(GenerationTaskRuntimeLifecycleService.class);
        taskRepository = mock(DurableGenerationTaskRepository.class);
        appStateService = mock(GenerationAppStateService.class);
        transaction = new GenerationTaskFinalizationTransaction(
                taskLifecycleService, runtimeLifecycleService, taskRepository,
                appStateService);
    }

    @Test
    void managedFinalizationMustPersistCompleteBusinessTerminalBeforeRuntimeConfirmation() {
        GenerationExecutionFence fence = new GenerationExecutionFence("task-1", "worker-a", 7L);
        GenerationFinalizationCommand command = GenerationFinalizationCommand.of(
                "task-1", 11L, fence, GenerationTaskStatus.SUCCESS,
                null, "任务成功", null);

        transaction.finalizeManaged(command);

        InOrder order = inOrder(taskLifecycleService, runtimeLifecycleService);
        order.verify(taskLifecycleService).finalizeGeneration(
                "task-1", 11L, 7L, GenerationTaskStatus.SUCCESS,
                null, "任务成功", null);
        order.verify(runtimeLifecycleService).persistOwnedCompletion(
                fence, GenerationTaskStatus.SUCCESS, null);
        verify(taskRepository).prepareFinalizationIntent(
                org.mockito.ArgumentMatchers.eq(command), any(Instant.class));
    }

    @Test
    void ownedRuntimeFinalizationMustPrepareEffectBeforeCompletingTheLease() {
        GenerationExecutionFence fence = new GenerationExecutionFence("task-owned", "worker-a", 3L);
        GenerationFinalizationCommand command = GenerationFinalizationCommand.of(
                "task-owned", 11L, fence, GenerationTaskStatus.FAILED,
                "build_failed", null, null);

        transaction.finalizeOwnedRuntime(command);

        InOrder order = inOrder(taskRepository, runtimeLifecycleService);
        order.verify(taskRepository).prepareFinalizationIntent(
                org.mockito.ArgumentMatchers.eq(command), any(Instant.class));
        order.verify(runtimeLifecycleService).persistOwnedCompletion(
                fence, GenerationTaskStatus.FAILED, "build_failed");
    }

    @Test
    void legacyManagedFinalizationWithoutFenceMustRemainCompatible() {
        GenerationFinalizationCommand command = GenerationFinalizationCommand.of(
                "task-legacy", 11L, null, GenerationTaskStatus.SUCCESS,
                null, "任务成功", null);

        transaction.finalizeManaged(command);

        verify(runtimeLifecycleService, never()).persistOwnedCompletion(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(taskLifecycleService).finalizeGeneration(
                "task-legacy", 11L, null, GenerationTaskStatus.SUCCESS,
                null, "任务成功", null);
    }

    @Test
    void unownedFinalizationMustReleaseApplicationOnlyAfterDurableTerminalWrite() {
        when(taskRepository.findByTaskId("task-remote"))
                .thenReturn(Optional.of(record()));

        transaction.finalizeUnownedRuntime(
                "task-remote", GenerationTaskStatus.CANCELLED, "user_requested");

        InOrder order = inOrder(runtimeLifecycleService, appStateService);
        order.verify(appStateService).lockGenerationState(11L);
        order.verify(runtimeLifecycleService).persistUnownedCompletion(
                "task-remote", GenerationTaskStatus.CANCELLED, "user_requested");
        order.verify(appStateService).releaseTerminalGenerationState(11L, "task-remote");
    }

    @Test
    void expiredLeaseFinalizationMustCommitWithoutWaitingForCreditSettlement() {
        Instant completedAt = Instant.parse("2026-08-14T00:00:00Z");
        GenerationTaskRecoveryCandidate candidate = recoveryCandidate("task-expired");
        when(taskRepository.finalizeExpiredLease(
                candidate, GenerationTaskStatus.FAILED, completedAt, "lease_expired"))
                .thenReturn(true);

        boolean finalized = transaction.finalizeExpiredLease(
                candidate, GenerationTaskStatus.FAILED, completedAt, "lease_expired");

        assertTrue(finalized);
        verify(appStateService).releaseOwnedGenerationState(11L, "task-expired", 3L);
    }

    @Test
    void expiredPublishedFinalizationMustCommitWithoutWaitingForCreditSettlement() {
        Instant completedAt = Instant.parse("2026-08-14T00:00:00Z");
        GenerationTaskRecoveryCandidate candidate = recoveryCandidate("task-published");
        GenerationFinalizationCommand command = GenerationFinalizationCommand.of(
                "task-published", 11L,
                new GenerationExecutionFence("task-published", "worker-a", 3L),
                GenerationTaskStatus.SUCCESS, null, "任务成功", null);
        when(taskRepository.finalizeExpiredPublishedTask(candidate, command, completedAt))
                .thenReturn(true);

        boolean finalized = transaction.finalizeExpiredPublishedTask(
                candidate, command, completedAt);

        assertTrue(finalized);
        verify(appStateService).releaseOwnedGenerationState(11L, "task-published", 3L);
    }

    private DurableGenerationTaskRecord record() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        return new DurableGenerationTaskRecord(
                "task-remote", 11L, 22L, 33L, "agent_edit",
                GenerationTaskStatus.WAITING_APPROVAL, "approval", "等待审批",
                now, now.plusSeconds(600), true, "user_requested",
                null, null, null, 1, 3L, null, null);
    }

    private GenerationTaskRecoveryCandidate recoveryCandidate(String taskId) {
        Instant now = Instant.parse("2026-08-14T00:00:00Z");
        return new GenerationTaskRecoveryCandidate(
                taskId, 11L, GenerationTaskStatus.RUNNING, "worker-a",
                now.minusSeconds(1), now.plusSeconds(60), false, null, 3L, 7L);
    }
}
