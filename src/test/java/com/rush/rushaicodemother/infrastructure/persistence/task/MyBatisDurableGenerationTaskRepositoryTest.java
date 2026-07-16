package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLeaseRenewal;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskSubmissionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisDurableGenerationTaskRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-16T08:00:00Z");

    private GenerationTaskRuntimeMapper mapper;
    private MyBatisDurableGenerationTaskRepository repository;

    @BeforeEach
    void setUp() {
        mapper = mock(GenerationTaskRuntimeMapper.class);
        repository = new MyBatisDurableGenerationTaskRepository(mapper);
    }

    @Test
    void createSubmittedMustMapDurableIdentityAndLeaseMetadata() {
        when(mapper.insertSubmittedTask(any())).thenReturn(1);
        GenerationTaskSubmissionRecord submitted = submission();

        repository.createSubmitted(submitted);

        ArgumentCaptor<GenerationTask> captor = ArgumentCaptor.forClass(GenerationTask.class);
        verify(mapper).insertSubmittedTask(captor.capture());
        GenerationTask entity = captor.getValue();
        assertEquals("task-1", entity.getTaskId());
        assertEquals("heavy_generation", entity.getRoute());
        assertEquals(toLocal(NOW.plusSeconds(30)), entity.getLeaseUntil());
        assertEquals(toLocal(NOW.plusSeconds(1_200)), entity.getDeadlineAt());
    }

    @Test
    void duplicateTaskIdMustBeIdempotentOnlyForSameStableIdentity() {
        when(mapper.insertSubmittedTask(any())).thenThrow(new DuplicateKeyException("uk_taskId"));
        when(mapper.selectRuntimeByTaskId("task-1")).thenReturn(runtimeEntity(
                GenerationTaskStatus.QUEUED, 2L, false));

        repository.createSubmitted(submission());

        when(mapper.selectRuntimeByTaskId("task-1")).thenReturn(runtimeEntity(
                GenerationTaskStatus.QUEUED, 99L, false));
        assertThrows(BusinessException.class, () -> repository.createSubmitted(submission()));
    }

    @Test
    void heartbeatMustReturnPersistedCancellationSignal() {
        when(mapper.renewOwnedLease(
                "task-1", "worker-a", toLocal(NOW), toLocal(NOW.plusSeconds(30))))
                .thenReturn(1);
        when(mapper.selectRuntimeByTaskId("task-1")).thenReturn(runtimeEntity(
                GenerationTaskStatus.RUNNING, 2L, true));

        GenerationTaskLeaseRenewal renewal = repository.renewLease(
                "task-1", "worker-a", NOW, NOW.plusSeconds(30));

        assertTrue(renewal.renewed());
        assertTrue(renewal.cancellationRequested());
        assertEquals("user_requested", renewal.cancellationReason());
    }

    @Test
    void terminalCompletionMustBeIdempotentButRejectConflictingStatus() {
        when(mapper.completeNonTerminalTask(
                "task-1", "success", null, toLocal(NOW))).thenReturn(0);
        when(mapper.selectRuntimeByTaskId("task-1")).thenReturn(runtimeEntity(
                GenerationTaskStatus.SUCCESS, 2L, false));

        repository.complete("task-1", GenerationTaskStatus.SUCCESS, null, "worker-a", NOW);

        when(mapper.completeNonTerminalTask(
                "task-1", "failed", "failed", toLocal(NOW))).thenReturn(0);
        assertThrows(BusinessException.class, () -> repository.complete(
                "task-1", GenerationTaskStatus.FAILED, "failed", "worker-a", NOW));
    }

    @Test
    void recoveryBatchMustBeBoundedAndVersionCasMustBePreserved() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.findExpiredLeases(NOW, 501));

        GenerationTask candidateEntity = GenerationTask.builder()
                .taskId("task-expired")
                .appId(1L)
                .status("running")
                .leaseOwner("worker-old")
                .leaseUntil(toLocal(NOW.minusSeconds(1)))
                .deadlineAt(toLocal(NOW.plusSeconds(60)))
                .cancellationRequested(0)
                .version(7L)
                .build();
        when(mapper.selectExpiredLeases(toLocal(NOW), 10)).thenReturn(List.of(candidateEntity));
        List<GenerationTaskRecoveryCandidate> candidates = repository.findExpiredLeases(NOW, 10);

        assertEquals(7L, candidates.getFirst().version());
        assertEquals(NOW.plusSeconds(60), candidates.getFirst().deadlineAt());
        assertFalse(candidates.getFirst().cancellationRequested());
        when(mapper.finalizeExpiredLease(
                "task-expired", "running", 7L, "failed", toLocal(NOW), "lease_expired"))
                .thenReturn(1);
        assertTrue(repository.finalizeExpiredLease(
                candidates.getFirst(), GenerationTaskStatus.FAILED, NOW, "lease_expired"
        ));
    }

    @Test
    void expiredLeaseFinalizationMustRejectSuccessStatus() {
        GenerationTaskRecoveryCandidate candidate = new GenerationTaskRecoveryCandidate(
                "task-expired", 1L, GenerationTaskStatus.RUNNING, "worker-old",
                NOW.minusSeconds(1), NOW.plusSeconds(60), false, null, 7L
        );

        assertThrows(IllegalArgumentException.class, () -> repository.finalizeExpiredLease(
                candidate, GenerationTaskStatus.SUCCESS, NOW, "invalid"
        ));
    }

    @Test
    void lostHeartbeatMustNotReadCancellationFromAnotherOwner() {
        when(mapper.renewOwnedLease(any(), any(), any(), any())).thenReturn(0);

        GenerationTaskLeaseRenewal renewal = repository.renewLease(
                "task-1", "worker-a", NOW, NOW.plusSeconds(30));

        assertFalse(renewal.renewed());
    }

    private GenerationTaskSubmissionRecord submission() {
        return new GenerationTaskSubmissionRecord(
                "task-1", 1L, 2L, "heavy_generation", NOW, NOW.plusSeconds(1_200),
                "worker-a", NOW.plusSeconds(30));
    }

    private GenerationTask runtimeEntity(GenerationTaskStatus status,
                                         Long userId,
                                         boolean cancellationRequested) {
        return GenerationTask.builder()
                .taskId("task-1")
                .appId(1L)
                .userId(userId)
                .route("heavy_generation")
                .status(status.getValue())
                .submittedAt(toLocal(NOW))
                .deadlineAt(toLocal(NOW.plusSeconds(1_200)))
                .cancellationRequested(cancellationRequested ? 1 : 0)
                .cancellationReason(cancellationRequested ? "user_requested" : null)
                .leaseOwner(status.isTerminal() ? null : "worker-a")
                .leaseUntil(status.isTerminal() ? null : toLocal(NOW.plusSeconds(30)))
                .heartbeatAt(status.isTerminal() ? null : toLocal(NOW))
                .attempt(status == GenerationTaskStatus.QUEUED ? 0 : 1)
                .version(3L)
                .endTime(status.isTerminal() ? toLocal(NOW) : null)
                .build();
    }

    private LocalDateTime toLocal(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }
}
