package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.mapper.GenerationTraceMapper;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommand;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommandCodec;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLease;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLeaseRenewal;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommandCodec;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisDurableGenerationTaskRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-16T08:00:00Z");
    private static final GenerationExecutionFence FENCE =
            new GenerationExecutionFence("task-1", "worker-a", 3L);
    private static final GenerationTaskLease LEASE =
            new GenerationTaskLease(FENCE, NOW.plusSeconds(30));

    private GenerationTaskRuntimeMapper mapper;
    private GenerationTraceMapper traceMapper;
    private MyBatisDurableGenerationTaskRepository repository;

    @BeforeEach
    void setUp() {
        mapper = mock(GenerationTaskRuntimeMapper.class);
        traceMapper = mock(GenerationTraceMapper.class);
        repository = new MyBatisDurableGenerationTaskRepository(mapper, traceMapper);
        when(mapper.lockActiveApplicationForSubmission(1L))
                .thenReturn(App.builder().id(1L).tenantId(100L).build());
        when(mapper.countNonTerminalTasksByAppId(1L)).thenReturn(0);
    }

    @Test
    void createSubmittedMustMapDurableIdentityAndReconstructableCommand() {
        when(mapper.insertSubmittedTask(any())).thenReturn(1);
        GenerationTaskSubmissionRecord submitted = submission();

        repository.createSubmitted(submitted);

        ArgumentCaptor<GenerationTask> captor = ArgumentCaptor.forClass(GenerationTask.class);
        verify(mapper).insertSubmittedTask(captor.capture());
        GenerationTask entity = captor.getValue();
        assertEquals("task-1", entity.getTaskId());
        assertEquals(100L, entity.getTenantId());
        assertEquals("a".repeat(64), entity.getIdempotencyKeyHash());
        assertEquals("b".repeat(64), entity.getRequestFingerprint());
        assertEquals("heavy_generation", entity.getRoute());
        assertEquals(64, entity.getIntentSignature().length());
        assertEquals("intent-profile-v1", entity.getIntentProfileVersion());
        assertEquals("routing-policy-v1", entity.getRouteDecisionVersion());
        assertTrue(entity.getRouteEvidenceJson().contains("\"selectedRoute\":\"heavy_generation\""));
        assertFalse(entity.getRouteEvidenceJson().contains("build application"));
        assertTrue(entity.getRouteAlternativesJson().contains("agent_edit"));
        assertEquals("routing-policy-v1@task-command-v" + GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                entity.getRouteReleaseIdentity());
        assertEquals(null, entity.getLeaseOwner());
        assertEquals(null, entity.getLeaseUntil());
        assertEquals(toLocal(NOW.plusSeconds(1_200)), entity.getDeadlineAt());
        assertEquals(GenerationTaskCommand.CURRENT_SCHEMA_VERSION, entity.getRuntimeSchemaVersion());
        assertEquals(submitted.command(), GenerationTaskCommandCodec.fromJson(entity.getRuntimePayloadJson()));
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
    void unexpectedIdempotencyUniqueConflictMustRollbackInsteadOfCommittingAnOrphanReservation() {
        when(mapper.insertSubmittedTask(any()))
                .thenThrow(new DuplicateKeyException("uk_generation_task_submission_idempotency"));

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> repository.createSubmitted(submission()));

        assertEquals(ErrorCode.CONFLICT_ERROR.getCode(), conflict.getCode());
    }

    @Test
    void heartbeatMustReturnPersistedCancellationSignal() {
        when(mapper.renewOwnedLease(
                "task-1", "worker-a", 3L, toLocal(NOW), toLocal(NOW.plusSeconds(30))))
                .thenReturn(1);
        when(mapper.selectRuntimeByTaskId("task-1")).thenReturn(runtimeEntity(
                GenerationTaskStatus.RUNNING, 2L, true));

        GenerationTaskLeaseRenewal renewal = repository.renewLease(
                LEASE, NOW, NOW.plusSeconds(30));

        assertTrue(renewal.renewed());
        assertTrue(renewal.cancellationRequested());
        assertEquals("user_requested", renewal.cancellationReason());
        assertEquals(LEASE, renewal.lease());
    }

    @Test
    void terminalCompletionMustBeIdempotentButRejectConflictingStatus() {
        when(mapper.completeOwnedTask(
                "task-1", "worker-a", 3L, "success", null, toLocal(NOW))).thenReturn(0);
        when(mapper.selectRuntimeByTaskId("task-1")).thenReturn(runtimeEntity(
                GenerationTaskStatus.SUCCESS, 2L, false));

        repository.completeOwned(LEASE, GenerationTaskStatus.SUCCESS, null, NOW);

        when(mapper.completeOwnedTask(
                "task-1", "worker-a", 3L, "failed", "failed", toLocal(NOW))).thenReturn(0);
        assertThrows(BusinessException.class, () -> repository.completeOwned(
                LEASE, GenerationTaskStatus.FAILED, "failed", NOW));
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
                .executionEpoch(4L)
                .version(7L)
                .build();
        when(mapper.selectExpiredLeases(toLocal(NOW), 10)).thenReturn(List.of(candidateEntity));
        List<GenerationTaskRecoveryCandidate> candidates = repository.findExpiredLeases(NOW, 10);

        assertEquals(7L, candidates.getFirst().version());
        assertEquals(NOW.plusSeconds(60), candidates.getFirst().deadlineAt());
        assertEquals(4L, candidates.getFirst().executionEpoch());
        assertFalse(candidates.getFirst().cancellationRequested());
        when(mapper.finalizeExpiredLease(
                eq("task-expired"), eq("running"), eq(7L), eq("failed"),
                eq(toLocal(NOW)), eq("lease_expired"), anyInt(), anyString(), eq(4L)))
                .thenReturn(1);
        assertTrue(repository.finalizeExpiredLease(
                candidates.getFirst(), GenerationTaskStatus.FAILED, NOW, "lease_expired"
        ));
    }

    @Test
    void unownedTerminalTransitionMustPersistAReplayableEffectInTheSameWrite() {
        GenerationTask waitingApproval = runtimeEntity(
                GenerationTaskStatus.WAITING_APPROVAL, 2L, true);
        waitingApproval.setLeaseOwner(null);
        waitingApproval.setLeaseUntil(null);
        when(mapper.selectRuntimeByTaskId("task-1")).thenReturn(waitingApproval);
        when(mapper.completeUnownedTask(
                eq("task-1"), eq("cancelled"), eq("user_requested"), eq(toLocal(NOW)),
                anyInt(), anyString(), eq(2L))).thenReturn(1);

        repository.completeUnowned(
                "task-1", GenerationTaskStatus.CANCELLED, "user_requested", NOW);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(mapper).completeUnownedTask(
                eq("task-1"), eq("cancelled"), eq("user_requested"), eq(toLocal(NOW)),
                eq(GenerationFinalizationCommandCodec.CURRENT_SCHEMA_VERSION),
                payload.capture(), eq(2L));
        GenerationFinalizationCommand command = GenerationFinalizationCommandCodec.fromJson(
                payload.getValue());
        assertEquals(GenerationTaskStatus.CANCELLED, command.status());
        assertEquals(2L, command.executionFence().executionEpoch());
    }

    @Test
    void expiredLeaseFinalizationMustRejectSuccessStatus() {
        GenerationTaskRecoveryCandidate candidate = new GenerationTaskRecoveryCandidate(
                "task-expired", 1L, GenerationTaskStatus.RUNNING, "worker-old",
                NOW.minusSeconds(1), NOW.plusSeconds(60), false, null, 1L, 7L
        );

        assertThrows(IllegalArgumentException.class, () -> repository.finalizeExpiredLease(
                candidate, GenerationTaskStatus.SUCCESS, NOW, "invalid"
        ));
    }

    @Test
    void lostHeartbeatMustNotReadCancellationFromAnotherOwner() {
        when(mapper.renewOwnedLease(any(), any(), anyLong(), any(), any())).thenReturn(0);

        GenerationTaskLeaseRenewal renewal = repository.renewLease(
                LEASE, NOW, NOW.plusSeconds(30));

        assertFalse(renewal.renewed());
    }

    @Test
    void approvalSuspensionAndRequeueMustUseExplicitStateTransitions() {
        when(mapper.suspendOwnedTaskForApproval(
                "task-1", "worker-a", 3L, "approval required", toLocal(NOW))).thenReturn(1);
        when(mapper.requeueWaitingApprovalTask(
                "task-1", "worker-b", toLocal(NOW), toLocal(NOW.plusSeconds(30)))).thenReturn(1);
        when(mapper.selectOwnedLease("task-1", "worker-b")).thenReturn(GenerationTask.builder()
                .taskId("task-1")
                .leaseOwner("worker-b")
                .leaseUntil(toLocal(NOW.plusSeconds(30)))
                .executionEpoch(5L)
                .build());
        when(mapper.restoreQueuedTaskToWaitingApproval(
                "task-1", "worker-b", 5L, "dispatch retry", toLocal(NOW))).thenReturn(1);

        assertTrue(repository.suspendForApproval(
                LEASE, "approval required", NOW));
        GenerationTaskLease resumedLease = repository.requeueAfterApproval(
                "task-1", "worker-b", NOW, NOW.plusSeconds(30)).orElseThrow();
        assertEquals(5L, resumedLease.executionEpoch());
        assertTrue(repository.restoreWaitingAfterDispatchFailure(
                resumedLease, "dispatch retry", NOW));
    }

    @Test
    void staleExecutionEpochMustBeRejectedForOwnedCompletion() {
        GenerationTaskLease staleLease = new GenerationTaskLease(
                new GenerationExecutionFence("task-1", "worker-a", 2L),
                NOW.plusSeconds(30));
        when(mapper.completeOwnedTask(
                "task-1", "worker-a", 2L, "success", null, toLocal(NOW))).thenReturn(0);
        when(mapper.selectRuntimeByTaskId("task-1")).thenReturn(runtimeEntity(
                GenerationTaskStatus.RUNNING, 2L, false));

        assertThrows(BusinessException.class, () -> repository.completeOwned(
                staleLease, GenerationTaskStatus.SUCCESS, null, NOW));
    }

    private GenerationTaskSubmissionRecord submission() {
        GenerationTaskCommand command = command();
        return new GenerationTaskSubmissionRecord(
                "task-1", 1L, 2L, 100L, "heavy_generation", NOW, NOW.plusSeconds(1_200),
                "a".repeat(64), "b".repeat(64), command);
    }

    private GenerationTask runtimeEntity(GenerationTaskStatus status,
                                         Long userId,
                                         boolean cancellationRequested) {
        return GenerationTask.builder()
                .taskId("task-1")
                .appId(1L)
                .userId(userId)
                .tenantId(100L)
                .idempotencyKeyHash("a".repeat(64))
                .requestFingerprint("b".repeat(64))
                .route("heavy_generation")
                .status(status.getValue())
                .submittedAt(toLocal(NOW))
                .deadlineAt(toLocal(NOW.plusSeconds(1_200)))
                .cancellationRequested(cancellationRequested ? 1 : 0)
                .cancellationReason(cancellationRequested ? "user_requested" : null)
                .executionEpoch(3L)
                .leaseOwner(status.isTerminal() ? null : "worker-a")
                .leaseUntil(status.isTerminal() ? null : toLocal(NOW.plusSeconds(30)))
                .heartbeatAt(status.isTerminal() ? null : toLocal(NOW))
                .runtimeSchemaVersion(GenerationTaskCommand.CURRENT_SCHEMA_VERSION)
                .runtimePayloadJson(GenerationTaskCommandCodec.toJson(command()))
                .attempt(status == GenerationTaskStatus.QUEUED ? 0 : 1)
                .version(3L)
                .endTime(status.isTerminal() ? toLocal(NOW) : null)
                .build();
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
                NOW.plusSeconds(1_200));
    }

    private LocalDateTime toLocal(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }
}
