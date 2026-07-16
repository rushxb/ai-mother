package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLeaseRenewal;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskSubmissionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskLeaseCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-07-16T02:00:00Z");
    private static final String OWNER = "worker-a";

    private DurableGenerationTaskRepository repository;
    private GenerationExecutionContextService executionContextService;
    private GenerationTaskLeaseCoordinator coordinator;

    @BeforeEach
    void setUp() {
        repository = mock(DurableGenerationTaskRepository.class);
        executionContextService = mock(GenerationExecutionContextService.class);
        GenerationTaskLeaseProperties properties = new GenerationTaskLeaseProperties();
        properties.setLeaseDuration(Duration.ofSeconds(30));
        GenerationTaskLeaseOwnerProvider ownerProvider = mock(GenerationTaskLeaseOwnerProvider.class);
        when(ownerProvider.ownerId()).thenReturn(OWNER);
        coordinator = new GenerationTaskLeaseCoordinator(
                repository, properties, ownerProvider, executionContextService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void submissionRecordMustPersistAbsoluteDeadlineAndInitialOwnedLease() {
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-submit", 1L, 2L, NOW,
                new GenerationRuntimeProperties().toLimits(), Clock.fixed(NOW, ZoneOffset.UTC));
        GenerationTaskExecution execution = new GenerationTaskExecution(
                "task-submit", new GenerationSession(null, context), context, NOW);

        GenerationTaskSubmissionRecord record = coordinator.submissionRecord(execution, "lightweight_edit");

        assertEquals(NOW, record.submittedAt());
        assertEquals(context.deadlineAt(), record.deadlineAt());
        assertEquals(NOW.plusSeconds(30), record.leaseUntil());
        assertEquals(OWNER, record.leaseOwner());
    }

    @Test
    void activateMustUseOwnerScopedLeaseCompareAndSet() {
        when(repository.activate("task-activate", OWNER, NOW, NOW.plusSeconds(30))).thenReturn(true);

        coordinator.activate("task-activate");

        verify(repository).activate("task-activate", OWNER, NOW, NOW.plusSeconds(30));
    }

    @Test
    void cancelledQueuedTaskMustCancelLocalContextInsteadOfStartingWork() {
        when(repository.activate("task-cancelled", OWNER, NOW, NOW.plusSeconds(30))).thenReturn(false);
        when(repository.findByTaskId("task-cancelled")).thenReturn(Optional.of(record(
                "task-cancelled", GenerationTaskStatus.QUEUED, true, "user_requested")));

        assertThrows(GenerationExecutionCancelledException.class,
                () -> coordinator.activate("task-cancelled"));

        verify(executionContextService).cancelByTaskId("task-cancelled", "user_requested");
    }

    @Test
    void terminalOrForeignOwnedTaskMustNeverBeActivated() {
        when(repository.activate("task-terminal", OWNER, NOW, NOW.plusSeconds(30))).thenReturn(false);
        when(repository.findByTaskId("task-terminal")).thenReturn(Optional.of(record(
                "task-terminal", GenerationTaskStatus.SUCCESS, false, null)));

        assertThrows(GenerationExecutionPolicyException.class,
                () -> coordinator.activate("task-terminal"));
    }

    @Test
    void heartbeatMustPropagateCancellationAndDropLostLease() {
        coordinator.trackSubmitted("task-cancel");
        coordinator.trackSubmitted("task-lost");
        when(repository.renewLease("task-cancel", OWNER, NOW, NOW.plusSeconds(30)))
                .thenReturn(new GenerationTaskLeaseRenewal(true, true, "operator_cancelled"));
        when(repository.renewLease("task-lost", OWNER, NOW, NOW.plusSeconds(30)))
                .thenReturn(GenerationTaskLeaseRenewal.lost());

        coordinator.heartbeatTrackedTasks();

        verify(executionContextService).cancelByTaskId("task-cancel", "operator_cancelled");
        verify(executionContextService).cancelByTaskId("task-lost", "worker_lease_lost");
        assertEquals(1, coordinator.trackedTaskCount());
        assertTrue(coordinator.ownerId().equals(OWNER));
    }

    private DurableGenerationTaskRecord record(String taskId,
                                               GenerationTaskStatus status,
                                               boolean cancellationRequested,
                                               String cancellationReason) {
        return new DurableGenerationTaskRecord(
                taskId, 1L, 2L, "lightweight_edit", status, "build", "正在构建", NOW, NOW.plusSeconds(600),
                cancellationRequested, cancellationReason, OWNER, NOW.plusSeconds(30), NOW,
                status == GenerationTaskStatus.QUEUED ? 0 : 1, 1L,
                status.isTerminal() ? NOW : null, null);
    }
}
