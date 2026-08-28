package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.config.AiToolApprovalProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolApprovalServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Test
    void approvalMustBePersistedAndExecutionResultCommittedAtomically() {
        ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);
        DurableGenerationTaskRepository tasks = taskRepository();
        String approvalId = "a".repeat(64);
        when(approvals.createPending(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(approvals.approve("task-1", 1L, DestructiveToolAction.SNAPSHOT_ROLLBACK,
                approvalId, 7L, NOW)).thenReturn(true);
        ToolApprovalRecord approved = executionRecord(
                approvalId, ToolApprovalStatus.APPROVED, null, null, 0);
        ToolApprovalRecord executing = executionRecord(
                approvalId, ToolApprovalStatus.EXECUTING, NOW, null, 1);
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(false, "rolled back");
        ToolApprovalRecord consumed = executionRecord(
                approvalId, ToolApprovalStatus.CONSUMED, NOW, outcome, 1);
        when(approvals.find("task-1", 1L, DestructiveToolAction.SNAPSHOT_ROLLBACK, approvalId))
                .thenReturn(Optional.of(approved), Optional.of(executing), Optional.of(consumed));
        when(approvals.beginExecution(
                "task-1", 1L, DestructiveToolAction.SNAPSHOT_ROLLBACK,
                approvalId, "call-1", NOW, 3)).thenReturn(true);
        when(approvals.completeExecution(
                "task-1", 1L, DestructiveToolAction.SNAPSHOT_ROLLBACK,
                approvalId, "call-1", outcome, NOW)).thenReturn(true);
        AiToolApprovalProperties properties = new AiToolApprovalProperties();
        ToolApprovalService service = service(approvals, tasks, properties);

        service.requestApproval(
                "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK, approvalId,
                java.util.Map.of("snapshotName", "safe"),
                approved.invocationCheckpoint()
        );
        service.approve("task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK, approvalId, 7L);

        verify(approvals).createPending(org.mockito.ArgumentMatchers.argThat(record ->
                record.appId().equals(11L)
                        && record.userId().equals(7L)
                        && record.requestExecutionEpoch() == 1L
                        && record.invocationCheckpoint().toolName().equals("manageSnapshot")
                        && record.invocationCheckpoint().argumentsDigest()
                        .equals(approved.invocationCheckpoint().argumentsDigest())
                        && record.expiresAt().equals(NOW.plus(properties.getTtl()))));
        ToolApprovalRecord started = service.beginExecution(approved);
        ToolApprovalRecord completed = service.completeExecution(started, outcome);

        assertTrue(started.status() == ToolApprovalStatus.EXECUTING);
        assertTrue(completed.status() == ToolApprovalStatus.CONSUMED);
        assertTrue(completed.executionOutcome().equals(outcome));
    }

    @Test
    void rejectionMustUseAtomicPendingTransition() {
        ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);
        when(approvals.reject("task-1", 1L, DestructiveToolAction.SNAPSHOT_DELETE,
                "b".repeat(64), 7L, NOW)).thenReturn(true);
        when(approvals.find("task-1", 1L, DestructiveToolAction.SNAPSHOT_DELETE, "b".repeat(64)))
                .thenReturn(Optional.of(record(
                        "b".repeat(64), DestructiveToolAction.SNAPSHOT_DELETE,
                        ToolApprovalStatus.REJECTED, 7L)));
        ToolApprovalService service = service(approvals, taskRepository(), new AiToolApprovalProperties());

        service.reject("task-1", DestructiveToolAction.SNAPSHOT_DELETE, "b".repeat(64), 7L);

        verify(approvals).reject("task-1", 1L, DestructiveToolAction.SNAPSHOT_DELETE,
                "b".repeat(64), 7L, NOW);
    }

    @Test
    void expirationMaintenanceMustBeBounded() {
        ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);
        AiToolApprovalProperties properties = new AiToolApprovalProperties();
        properties.setExpirationBatchSize(25);
        ToolApprovalService service = service(approvals, taskRepository(), properties);

        service.expireApprovals();

        verify(approvals).expireBefore(NOW, 25);
    }

    @Test
    void repeatedDecisionMustOnlyBeIdempotentForSameActorAndOutcome() {
        String approvalId = "c".repeat(64);
        ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);
        when(approvals.find("task-1", 1L, DestructiveToolAction.SNAPSHOT_ROLLBACK, approvalId))
                .thenReturn(Optional.of(record(
                        approvalId, DestructiveToolAction.SNAPSHOT_ROLLBACK,
                        ToolApprovalStatus.APPROVED, 7L)));
        ToolApprovalService service = service(
                approvals, taskRepository(), new AiToolApprovalProperties());

        service.approve("task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK, approvalId, 7L);

        assertThrows(com.rush.rushaicodemother.exception.BusinessException.class,
                () -> service.approve(
                        "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK, approvalId, 8L));
    }

    @Test
    void expiredApprovalMustNotBeApprovedOrAuthorizeExecution() {
        String approvalId = "d".repeat(64);
        ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);
        when(approvals.find("task-1", 1L, DestructiveToolAction.SNAPSHOT_DELETE, approvalId))
                .thenReturn(Optional.of(record(
                        approvalId, DestructiveToolAction.SNAPSHOT_DELETE,
                        ToolApprovalStatus.EXPIRED, null)));
        ToolApprovalService service = service(
                approvals, taskRepository(), new AiToolApprovalProperties());

        assertThrows(com.rush.rushaicodemother.exception.BusinessException.class,
                () -> service.approve(
                        "task-1", DestructiveToolAction.SNAPSHOT_DELETE, approvalId, 7L));
        assertFalse(service.isExecutionAuthorized(
                "task-1", DestructiveToolAction.SNAPSHOT_DELETE, approvalId,
                new GenerationToolExecutionContextService.ToolInvocationExecution(
                        "task-1", 1L, "call-1", "manageSnapshot", "a".repeat(64))));
    }

    @Test
    void executionAuthorizationMustMatchImmutableApprovalRequestEpoch() {
        String approvalId = "9".repeat(64);
        ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);
        ToolApprovalRecord executing = executionRecord(
                approvalId, ToolApprovalStatus.EXECUTING, NOW, null, 1);
        when(approvals.find(
                "task-1", 1L, DestructiveToolAction.SNAPSHOT_ROLLBACK, approvalId))
                .thenReturn(Optional.of(executing));
        ToolApprovalService service = service(
                approvals, taskRepository(), new AiToolApprovalProperties());
        ToolInvocationCheckpoint checkpoint = executing.invocationCheckpoint();

        assertTrue(service.isExecutionAuthorized(
                "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK, approvalId,
                new GenerationToolExecutionContextService.ToolInvocationExecution(
                        "task-1", 1L, checkpoint.requestId(), checkpoint.toolName(),
                        checkpoint.argumentsDigest())));
        assertFalse(service.isExecutionAuthorized(
                "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK, approvalId,
                new GenerationToolExecutionContextService.ToolInvocationExecution(
                        "task-1", 2L, checkpoint.requestId(), checkpoint.toolName(),
                        checkpoint.argumentsDigest())));
    }

    @Test
    void terminalOrCancelledTaskMustRejectLateApprovalDecisions() {
        DurableGenerationTaskRepository tasks = mock(DurableGenerationTaskRepository.class);
        when(tasks.findByTaskId("task-1")).thenReturn(Optional.of(task(
                GenerationTaskStatus.SUCCESS, false)));
        ToolApprovalService service = service(
                mock(ToolApprovalRepository.class), tasks, new AiToolApprovalProperties());

        assertThrows(com.rush.rushaicodemother.exception.BusinessException.class,
                () -> service.approve(
                        "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK,
                        "e".repeat(64), 7L));

        when(tasks.findByTaskId("task-1")).thenReturn(Optional.of(task(
                GenerationTaskStatus.WAITING_APPROVAL, true)));
        assertThrows(com.rush.rushaicodemother.exception.BusinessException.class,
                () -> service.reject(
                        "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK,
                        "e".repeat(64), 7L));
    }

    @Test
    void invocationCheckpointMustOnlyAttachWhileTaskIsRunning() {
        String approvalId = "f".repeat(64);
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                "call-1",
                "manageSnapshot",
                "{}",
                "{\"taskId\":\"task-1\"}",
                NOW
        );
        ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);
        ToolApprovalRecord persisted = record(
                approvalId, DestructiveToolAction.SNAPSHOT_ROLLBACK,
                ToolApprovalStatus.PENDING, null);
        persisted = new ToolApprovalRecord(
                persisted.approvalId(), persisted.taskId(), persisted.requestExecutionEpoch(),
                persisted.appId(), persisted.userId(),
                persisted.action(), persisted.requestJson(), persisted.status(), persisted.requestedAt(),
                persisted.expiresAt(), persisted.decidedBy(), persisted.decidedAt(), persisted.consumedAt(),
                persisted.version(), checkpoint);
        when(approvals.attachInvocationCheckpoint(
                "task-1", 1L, DestructiveToolAction.SNAPSHOT_ROLLBACK, approvalId, checkpoint))
                .thenReturn(persisted);
        ToolApprovalService service = service(
                approvals, taskRepository(), new AiToolApprovalProperties());

        ToolApprovalRecord result = service.attachInvocationCheckpoint(
                "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK, approvalId, checkpoint);

        assertTrue(result.invocationCheckpoint() == checkpoint);
    }

    @Test
    void taskSubmitterWithoutCurrentDeveloperRoleMustNotApproveDestructiveTool() {
        ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);
        TenantAuthorizationService authorizationService = mock(TenantAuthorizationService.class);
        doThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR, "审批人权限已失效"))
                .when(authorizationService)
                .requireRole(100L, 7L, TenantRole.DEVELOPER, "无权决策该生成任务的工具审批");
        ToolApprovalService service = service(
                approvals, taskRepository(), new AiToolApprovalProperties(), authorizationService);

        assertThrows(BusinessException.class, () -> service.approve(
                "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK, "8".repeat(64), 7L));

        verify(approvals, never()).approve(any(), any(Long.class), any(), any(), any(), any());
    }

    private ToolApprovalService service(ToolApprovalRepository approvals,
                                        DurableGenerationTaskRepository tasks,
                                        AiToolApprovalProperties properties) {
        return service(approvals, tasks, properties, mock(TenantAuthorizationService.class));
    }

    private ToolApprovalService service(ToolApprovalRepository approvals,
                                        DurableGenerationTaskRepository tasks,
                                        AiToolApprovalProperties properties,
                                        TenantAuthorizationService authorizationService) {
        return new ToolApprovalService(
                approvals,
                tasks,
                properties,
                mock(GenerationSessionRegistry.class),
                authorizationService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private DurableGenerationTaskRepository taskRepository() {
        DurableGenerationTaskRepository repository = mock(DurableGenerationTaskRepository.class);
        when(repository.findByTaskId(eq("task-1"))).thenReturn(Optional.of(task(
                GenerationTaskStatus.RUNNING, false)));
        return repository;
    }

    private DurableGenerationTaskRecord task(GenerationTaskStatus status,
                                              boolean cancellationRequested) {
        boolean leased = status == GenerationTaskStatus.RUNNING;
        return new DurableGenerationTaskRecord(
                "task-1", 11L, 7L, 100L, "heavy", status,
                "codegen", "running", NOW.minusSeconds(5), NOW.plusSeconds(600),
                cancellationRequested, cancellationRequested ? "user_requested" : null,
                leased ? "worker-1" : null, leased ? NOW.plusSeconds(30) : null,
                leased ? NOW : null, 1L, 1, 1,
                status.isTerminal() ? NOW : null, null
        );
    }

    private ToolApprovalRecord record(String approvalId,
                                      DestructiveToolAction action,
                                      ToolApprovalStatus status,
                                      Long decidedBy) {
        return new ToolApprovalRecord(
                approvalId, "task-1", 1L, 11L, 7L, action, "{}", status,
                NOW.minusSeconds(30), NOW.plusSeconds(600), decidedBy,
                decidedBy == null ? null : NOW, null, 1, null
        );
    }

    private ToolApprovalRecord executionRecord(
            String approvalId,
            ToolApprovalStatus status,
            Instant executionStartedAt,
            ToolExecutionOutcome outcome,
            int executionAttempt
    ) {
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                "call-1", "manageSnapshot", "{}", "{\"taskId\":\"task-1\"}", NOW);
        return new ToolApprovalRecord(
                approvalId, "task-1", 1L, 11L, 7L,
                DestructiveToolAction.SNAPSHOT_ROLLBACK, "{}", status,
                NOW.minusSeconds(30), NOW.plusSeconds(600), 7L, NOW,
                status == ToolApprovalStatus.CONSUMED ? NOW : null,
                1, checkpoint, executionStartedAt, outcome, executionAttempt
        );
    }
}
