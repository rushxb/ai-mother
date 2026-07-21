package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskSubmissionRecord;
import com.rush.rushaicodemother.service.UserCreditService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskRuntimeLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T03:00:00Z");
    private static final GenerationExecutionFence FENCE =
            new GenerationExecutionFence("task-1", "owner-a", 3L);

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
    void submitMustPersistDurableUnownedCommandBeforeDispatch() {
        GenerationTaskCommand command = command();
        GenerationTaskSubmissionRecord record = new GenerationTaskSubmissionRecord(
                "task-1", 1L, 2L, 100L, "heavy_generation", NOW, NOW.plusSeconds(600), command);
        when(leaseCoordinator.submissionRecord(command, GenerationTaskIdempotency.none()))
                .thenReturn(record);

        service.submit(command);

        InOrder order = inOrder(repository, leaseCoordinator);
        order.verify(leaseCoordinator).submissionRecord(command, GenerationTaskIdempotency.none());
        order.verify(repository).createSubmitted(record);
    }

    @Test
    void ownedCompletionMustReleaseTrackingEvenWhenPersistenceFails() {
        doThrow(new IllegalStateException("db unavailable"))
                .when(leaseCoordinator).completeOwned(
                        FENCE, GenerationTaskStatus.FAILED, "failed", NOW);

        assertThrows(IllegalStateException.class,
                () -> service.completeOwned(FENCE, GenerationTaskStatus.FAILED, "failed"));

        verify(leaseCoordinator).release(FENCE);
    }

    @Test
    void activateAndCancellationMustDelegateThroughNarrowRuntimePorts() {
        service.activate(FENCE);
        service.requestCancellation("task-1", "user_requested");

        verify(leaseCoordinator).activate(FENCE);
        verify(repository).requestCancellation("task-1", "user_requested", NOW);
    }

    @Test
    void firstActivationMustRecordDurableQueueWaitExactlyAtRunningBoundary() {
        GenerationPerformanceMonitorService performanceMonitorService =
                mock(GenerationPerformanceMonitorService.class);
        service = new GenerationTaskRuntimeLifecycleService(
                repository, leaseCoordinator, null, performanceMonitorService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        DurableGenerationTaskRecord task = taskRecord(0, NOW.minusSeconds(15));
        when(repository.findByTaskId("task-1")).thenReturn(Optional.of(task));

        service.activate(FENCE);

        InOrder order = inOrder(leaseCoordinator, performanceMonitorService);
        order.verify(leaseCoordinator).activate(FENCE);
        order.verify(performanceMonitorService).recordSpan(
                "task-1",
                "durable_queue_wait",
                GenerationSpanCategory.QUEUE,
                "success",
                Duration.ofSeconds(15),
                "attempt=0"
        );
    }

    @Test
    void resumedActivationMustNotRecordInitialDurableQueueWaitAgain() {
        GenerationPerformanceMonitorService performanceMonitorService =
                mock(GenerationPerformanceMonitorService.class);
        service = new GenerationTaskRuntimeLifecycleService(
                repository, leaseCoordinator, null, performanceMonitorService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.findByTaskId("task-1"))
                .thenReturn(Optional.of(taskRecord(1, NOW.minusSeconds(30))));

        service.activate(FENCE);

        verify(leaseCoordinator).activate(FENCE);
        verify(performanceMonitorService, never()).recordSpan(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void approvalSuspensionAndRequeueMustRemainLeaseCoordinatorOperations() {
        GenerationExecutionFence resumedFence =
                new GenerationExecutionFence("task-1", "owner-a", 5L);
        when(leaseCoordinator.suspendForApproval(FENCE, "approval required")).thenReturn(true);
        when(leaseCoordinator.requeueAfterApproval("task-1"))
                .thenReturn(Optional.of(resumedFence));

        assertTrue(service.suspendForApproval(FENCE, "approval required"));
        assertEquals(resumedFence, service.requeueAfterApproval("task-1").orElseThrow());

        verify(leaseCoordinator).suspendForApproval(FENCE, "approval required");
        verify(leaseCoordinator).requeueAfterApproval("task-1");
    }

    @Test
    void ownedCompletionMustRecordEndToEndWaitFromDurableSubmissionTime() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GenerationOrchestrationMetricsCollector metricsCollector =
                new GenerationOrchestrationMetricsCollector(meterRegistry);
        service = new GenerationTaskRuntimeLifecycleService(
                repository, leaseCoordinator, metricsCollector, Clock.fixed(NOW, ZoneOffset.UTC));
        DurableGenerationTaskRecord task = mock(DurableGenerationTaskRecord.class);
        when(task.route()).thenReturn("agent_edit");
        when(task.submittedAt()).thenReturn(NOW.minusSeconds(12));
        when(repository.findByTaskId("task-edit")).thenReturn(Optional.of(task));
        GenerationExecutionFence editFence =
                new GenerationExecutionFence("task-edit", "owner-a", 4L);

        service.completeOwned(editFence, GenerationTaskStatus.SUCCESS, null);

        assertEquals(12.0,
                meterRegistry.find("generation_orchestration_user_wait_duration_seconds")
                        .tag("orchestration_mode", "agent_edit")
                        .tag("target_type", "unknown")
                        .tag("status", "success")
                        .timer()
                        .totalTime(java.util.concurrent.TimeUnit.SECONDS),
                0.001);
        verify(leaseCoordinator).release(editFence);
    }

    @Test
    void everyOwnedTerminalPathMustSettleCreditBeforeLeaseRelease() {
        UserCreditService creditService = mock(UserCreditService.class);
        service = new GenerationTaskRuntimeLifecycleService(
                repository,
                leaseCoordinator,
                null,
                null,
                creditService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        service.completeOwned(FENCE, GenerationTaskStatus.CANCELLED, "user_requested");

        InOrder order = inOrder(creditService, leaseCoordinator);
        order.verify(leaseCoordinator).completeOwned(
                FENCE, GenerationTaskStatus.CANCELLED, "user_requested", NOW);
        order.verify(creditService).chargeGenerationTask("task-1");
        order.verify(leaseCoordinator).release(FENCE);
    }

    @Test
    void unownedTerminalPathMustUseTheControlPlaneTransitionWithoutLeaseRelease() {
        service.completeUnowned("task-1", GenerationTaskStatus.CANCELLED, "queued_cancelled");

        verify(repository).completeUnowned(
                "task-1", GenerationTaskStatus.CANCELLED, "queued_cancelled", NOW);
        verify(leaseCoordinator, never()).release(FENCE);
    }

    private GenerationTaskCommand command() {
        return new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                "task-1",
                1L,
                2L,
                100L,
                "build application",
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationMode.HEAVY_EXPERT,
                0.95,
                "test",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD,
                "",
                NOW,
                NOW.plusSeconds(600));
    }

    private DurableGenerationTaskRecord taskRecord(int attempt, Instant submittedAt) {
        return new DurableGenerationTaskRecord(
                "task-1",
                1L,
                2L,
                100L,
                "heavy_generation",
                GenerationTaskStatus.QUEUED,
                "queued",
                "queued",
                submittedAt,
                NOW.plusSeconds(600),
                false,
                null,
                "owner-a",
                NOW.plusSeconds(30),
                NOW,
                attempt,
                1,
                null,
                null
        );
    }
}
