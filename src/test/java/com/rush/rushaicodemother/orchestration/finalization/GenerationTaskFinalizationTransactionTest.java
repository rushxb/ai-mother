package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.service.UserCreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskFinalizationTransactionTest {

    private GenerationTaskLifecycleService taskLifecycleService;
    private GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private DurableGenerationTaskRepository taskRepository;
    private GenerationAppStateService appStateService;
    private UserCreditService userCreditService;
    private GenerationTaskFinalizationTransaction transaction;

    @BeforeEach
    void setUp() {
        taskLifecycleService = mock(GenerationTaskLifecycleService.class);
        runtimeLifecycleService = mock(GenerationTaskRuntimeLifecycleService.class);
        taskRepository = mock(DurableGenerationTaskRepository.class);
        appStateService = mock(GenerationAppStateService.class);
        userCreditService = mock(UserCreditService.class);
        transaction = new GenerationTaskFinalizationTransaction(
                taskLifecycleService, runtimeLifecycleService, taskRepository,
                appStateService, userCreditService);
    }

    @Test
    void managedFinalizationMustHaveOneLifecycleAndSettlementOwner() {
        GenerationExecutionFence fence = new GenerationExecutionFence("task-1", "worker-a", 7L);
        GenerationFinalizationCommand command = GenerationFinalizationCommand.of(
                "task-1", 11L, fence, GenerationTaskStatus.SUCCESS,
                null, "任务成功", null);

        transaction.finalizeManaged(command);

        verify(taskLifecycleService).finalizeGeneration(
                "task-1", 11L, 7L, GenerationTaskStatus.SUCCESS,
                null, "任务成功", null);
        verify(runtimeLifecycleService, never()).persistOwnedCompletion(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(userCreditService, never()).chargeGenerationTask("task-1");
    }

    @Test
    void unownedFinalizationMustReleaseApplicationOnlyAfterDurableTerminalWrite() {
        when(taskRepository.findByTaskId("task-remote"))
                .thenReturn(Optional.of(record()));

        transaction.finalizeUnownedRuntime(
                "task-remote", GenerationTaskStatus.CANCELLED, "user_requested");

        InOrder order = inOrder(runtimeLifecycleService, appStateService, userCreditService);
        order.verify(appStateService).lockGenerationState(11L);
        order.verify(runtimeLifecycleService).persistUnownedCompletion(
                "task-remote", GenerationTaskStatus.CANCELLED, "user_requested");
        order.verify(appStateService).releaseTerminalGenerationState(11L, "task-remote");
        order.verify(userCreditService).chargeGenerationTask("task-remote");
    }

    private DurableGenerationTaskRecord record() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        return new DurableGenerationTaskRecord(
                "task-remote", 11L, 22L, 33L, "agent_edit",
                GenerationTaskStatus.WAITING_APPROVAL, "approval", "等待审批",
                now, now.plusSeconds(600), true, "user_requested",
                null, null, null, 1, 3L, null, null);
    }
}
