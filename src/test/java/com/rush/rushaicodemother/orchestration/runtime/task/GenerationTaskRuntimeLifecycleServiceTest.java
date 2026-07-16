package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskSubmissionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskRuntimeLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T03:00:00Z");

    private DurableGenerationTaskRepository repository;
    private GenerationTaskLeaseCoordinator leaseCoordinator;
    private GenerationTaskRuntimeLifecycleService service;

    @BeforeEach
    void setUp() {
        repository = mock(DurableGenerationTaskRepository.class);
        leaseCoordinator = mock(GenerationTaskLeaseCoordinator.class);
        service = new GenerationTaskRuntimeLifecycleService(
                repository, leaseCoordinator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void submitMustPersistDurableShellBeforeTrackingLocalLease() {
        GenerationTaskExecution execution = mock(GenerationTaskExecution.class);
        GenerationTaskSubmissionRecord record = new GenerationTaskSubmissionRecord(
                "task-1", 1L, 2L, "heavy_generation", NOW, NOW.plusSeconds(600),
                "owner-a", NOW.plusSeconds(30));
        when(execution.taskId()).thenReturn("task-1");
        when(leaseCoordinator.submissionRecord(execution, "heavy_generation")).thenReturn(record);

        service.submit(execution, "heavy_generation");

        InOrder order = inOrder(repository, leaseCoordinator);
        order.verify(repository).createSubmitted(record);
        order.verify(leaseCoordinator).trackSubmitted("task-1");
    }

    @Test
    void completionMustReleaseTrackingEvenWhenPersistenceFails() {
        when(leaseCoordinator.ownerId()).thenReturn("owner-a");
        org.mockito.Mockito.doThrow(new IllegalStateException("db unavailable"))
                .when(repository).complete(
                        "task-1", GenerationTaskStatus.FAILED, "failed", "owner-a", NOW);

        assertThrows(IllegalStateException.class,
                () -> service.complete("task-1", GenerationTaskStatus.FAILED, "failed"));

        verify(leaseCoordinator).release("task-1");
    }

    @Test
    void activateAndCancellationMustDelegateThroughNarrowRuntimePorts() {
        service.activate("task-1");
        service.requestCancellation("task-1", "user_requested");

        verify(leaseCoordinator).activate("task-1");
        verify(repository).requestCancellation("task-1", "user_requested", NOW);
    }
}
