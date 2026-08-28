package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.testing.GenerationFailureMatrix;
import com.rush.rushaicodemother.testing.GenerationFailureEvidence;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLease;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLeaseRenewal;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskSubmissionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag(GenerationFailureMatrix.TAG)
class GenerationTaskLeaseCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-07-16T02:00:00Z");
    private static final String OWNER = "worker-a";

    private DurableGenerationTaskRepository repository;
    private GenerationExecutionContextService executionContextService;
    private MutableClock clock;
    private GenerationTaskLeaseCoordinator coordinator;

    @BeforeEach
    void setUp() {
        repository = mock(DurableGenerationTaskRepository.class);
        executionContextService = mock(GenerationExecutionContextService.class);
        GenerationTaskLeaseProperties properties = new GenerationTaskLeaseProperties();
        properties.setLeaseDuration(Duration.ofSeconds(30));
        GenerationTaskLeaseOwnerProvider ownerProvider = mock(GenerationTaskLeaseOwnerProvider.class);
        when(ownerProvider.ownerId()).thenReturn(OWNER);
        clock = new MutableClock(NOW);
        coordinator = new GenerationTaskLeaseCoordinator(
                repository, properties, ownerProvider, executionContextService, clock);
    }

    @Test
    void submissionRecordMustPersistReconstructableCommandWithoutWorkerLease() {
        GenerationTaskCommand command = command("task-submit");

        GenerationTaskSubmissionRecord record = coordinator.submissionRecord(command);

        assertEquals(NOW, record.submittedAt());
        assertEquals(NOW.plusSeconds(600), record.deadlineAt());
        assertEquals("lightweight_edit", record.route());
        assertEquals(100L, record.tenantId());
        assertEquals(command, record.command());
    }

    @Test
    void reserveAndActivateMustUseTheSameOwnerAndEpochScopedLease() {
        GenerationTaskLease lease = lease("task-activate", 3L, NOW.plusSeconds(30));
        when(repository.reserveQueued(
                "task-activate", OWNER, NOW, NOW.plusSeconds(30)))
                .thenReturn(Optional.of(lease));
        when(repository.activate(lease, NOW, NOW.plusSeconds(30))).thenReturn(true);

        GenerationExecutionFence fence = coordinator.reserveQueued("task-activate").orElseThrow();
        coordinator.activate(fence);

        assertEquals(lease.fence(), fence);
        verify(repository).activate(lease, NOW, NOW.plusSeconds(30));
    }

    @Test
    @GenerationFailureEvidence("cancellation_queued_activation_fenced")
    void cancelledQueuedTaskMustCancelLocalContextInsteadOfStartingWork() {
        GenerationTaskLease lease = reserve("task-cancelled", 2L);
        assertEquals(2L, lease.executionEpoch());
        when(repository.activate(lease, NOW, NOW.plusSeconds(30))).thenReturn(false);
        when(repository.findByTaskId("task-cancelled")).thenReturn(Optional.of(record(
                "task-cancelled", GenerationTaskStatus.QUEUED, true, "user_requested")));

        assertThrows(GenerationExecutionCancelledException.class,
                () -> coordinator.activate(lease.fence()));

        verify(executionContextService).cancelByTaskId("task-cancelled", "user_requested");
    }

    @Test
    void terminalOrForeignOwnedTaskMustNeverBeActivated() {
        GenerationTaskLease lease = reserve("task-terminal", 4L);
        when(repository.activate(lease, NOW, NOW.plusSeconds(30))).thenReturn(false);
        when(repository.findByTaskId("task-terminal")).thenReturn(Optional.of(record(
                "task-terminal", GenerationTaskStatus.SUCCESS, false, null)));

        assertThrows(GenerationExecutionPolicyException.class,
                () -> coordinator.activate(lease.fence()));
    }

    @Test
    @GenerationFailureEvidence("cancellation_heartbeat_propagated")
    void heartbeatMustPropagateCancellationAndDropLostLease() {
        GenerationTaskLease cancelLease = reserve("task-cancel", 3L);
        GenerationTaskLease lostLease = reserve("task-lost", 6L);
        assertEquals(3L, cancelLease.executionEpoch());
        assertEquals(6L, lostLease.executionEpoch());
        when(repository.renewLease(cancelLease, NOW, NOW.plusSeconds(30)))
                .thenReturn(GenerationTaskLeaseRenewal.renewed(
                        cancelLease.renewedUntil(NOW.plusSeconds(30)), true, "operator_cancelled"));
        when(repository.renewLease(lostLease, NOW, NOW.plusSeconds(30)))
                .thenReturn(GenerationTaskLeaseRenewal.lost());

        coordinator.heartbeatTrackedTasks();

        verify(executionContextService).cancelByTaskId("task-cancel", "operator_cancelled");
        verify(executionContextService).cancelByTaskId("task-lost", "worker_lease_lost");
        assertEquals(1, coordinator.trackedTaskCount());
        assertEquals(OWNER, coordinator.ownerId());
    }

    @Test
    void heartbeatFailureMustBeToleratedOnlyUntilTheLastConfirmedLocalDeadline() {
        GenerationTaskLease lease = reserve("task-db-outage", 8L);
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository).renewLease(
                        lease, NOW, NOW.plusSeconds(30));

        coordinator.heartbeatTrackedTasks();

        verify(executionContextService, never()).cancelByTaskId(
                "task-db-outage", "worker_lease_expired_locally");
        assertEquals(1, coordinator.trackedTaskCount());

        clock.advance(Duration.ofSeconds(31));
        doThrow(new IllegalStateException("database still unavailable"))
                .when(repository).renewLease(
                        lease, NOW.plusSeconds(31), NOW.plusSeconds(61));

        coordinator.heartbeatTrackedTasks();

        verify(executionContextService).cancelByTaskId(
                "task-db-outage", "worker_lease_expired_locally");
        assertEquals(0, coordinator.trackedTaskCount());
    }

    @Test
    void heartbeatMustStopRenewingALocallyCancelledOrExpiredExecution() {
        GenerationTaskLease lease = reserve("task-local-stop", 9L);
        when(executionContextService.shouldStop("task-local-stop")).thenReturn(true);

        coordinator.heartbeatTrackedTasks();

        verify(repository, never()).renewLease(
                lease, NOW, NOW.plusSeconds(30));
        assertEquals(0, coordinator.trackedTaskCount());
    }

    @Test
    void approvalRequeueMustReturnAHigherEpochAndRejectTheOldFence() {
        GenerationTaskLease initialLease = reserve("task-approval", 3L);
        when(repository.suspendForApproval(
                initialLease, "approval required", NOW)).thenReturn(true);
        GenerationTaskLease resumedLease = lease("task-approval", 5L, NOW.plusSeconds(30));
        when(repository.requeueAfterApproval(
                "task-approval", OWNER, NOW, NOW.plusSeconds(30)))
                .thenReturn(Optional.of(resumedLease));

        assertTrue(coordinator.suspendForApproval(initialLease.fence(), "approval required"));
        GenerationExecutionFence resumedFence =
                coordinator.requeueAfterApproval("task-approval").orElseThrow();

        assertEquals(5L, resumedFence.executionEpoch());
        assertThrows(GenerationExecutionPolicyException.class,
                () -> coordinator.suspendForApproval(initialLease.fence(), "stale"));
        verify(repository, never()).suspendForApproval(initialLease, "stale", NOW);
        assertEquals(1, coordinator.trackedTaskCount());
    }

    private GenerationTaskLease reserve(String taskId, long executionEpoch) {
        GenerationTaskLease lease = lease(taskId, executionEpoch, NOW.plusSeconds(30));
        when(repository.reserveQueued(taskId, OWNER, NOW, NOW.plusSeconds(30)))
                .thenReturn(Optional.of(lease));
        assertEquals(lease.fence(), coordinator.reserveQueued(taskId).orElseThrow());
        return lease;
    }

    private GenerationTaskLease lease(String taskId, long executionEpoch, Instant leaseUntil) {
        return new GenerationTaskLease(
                new GenerationExecutionFence(taskId, OWNER, executionEpoch), leaseUntil);
    }

    private DurableGenerationTaskRecord record(String taskId,
                                               GenerationTaskStatus status,
                                               boolean cancellationRequested,
                                               String cancellationReason) {
        return new DurableGenerationTaskRecord(
                taskId, 1L, 2L, 100L, "lightweight_edit", status, "build", "正在构建",
                NOW, NOW.plusSeconds(600), cancellationRequested, cancellationReason,
                OWNER, NOW.plusSeconds(30), NOW,
                status == GenerationTaskStatus.QUEUED ? 0 : 1, 1L,
                status.isTerminal() ? NOW : null, null);
    }

    private GenerationTaskCommand command(String taskId) {
        return new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                taskId,
                1L,
                2L,
                100L,
                "update title",
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationMode.LIGHT_EDIT,
                0.9,
                "test",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD,
                "",
                NOW,
                NOW.plusSeconds(600));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
