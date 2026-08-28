package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionProperties;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTaskFinalizer;
import com.rush.rushaicodemother.orchestration.delivery.GenerationCostSummary;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimator;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.credit.GenerationTaskCostProjectionService;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class GenerationTaskQueryServiceTest {

    private GenerationSessionRegistry registry;
    private GenerationExecutionContextService contextService;
    private GenerationTaskQueryService queryService;
    private DurableGenerationTaskRepository durableRepository;
    private GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private TenantAuthorizationService tenantAuthorizationService;
    private GenerationTaskCostProjectionService costProjectionService;
    private User owner;

    @BeforeEach
    void setUp() {
        registry = new GenerationSessionRegistry(new GenerationSessionProperties());
        contextService = new GenerationExecutionContextService(new GenerationRuntimeProperties());
        durableRepository = mock(DurableGenerationTaskRepository.class);
        runtimeLifecycleService = mock(GenerationTaskRuntimeLifecycleService.class);
        tenantAuthorizationService = mock(TenantAuthorizationService.class);
        costProjectionService = mock(GenerationTaskCostProjectionService.class);
        queryService = new GenerationTaskQueryService(
                registry, durableRepository, mock(GenerationTaskProgressEstimator.class),
                mock(GenerationEventStream.class), mock(AppPersistenceService.class),
                tenantAuthorizationService, costProjectionService);
        owner = user(2L);
    }

    @Test
    void queryMustExposeRouteDeadlineAndTaskWideBudgets() {
        register("task-query", owner);

        GenerationTaskSnapshot snapshot = queryService.get("task-query", owner);

        assertEquals("task-query", snapshot.taskId());
        assertEquals("lightweight_edit", snapshot.route());
        assertEquals("running", snapshot.status());
        assertTrue(snapshot.deadlineAt().isAfter(snapshot.submittedAt()));
        assertEquals(snapshot.limits().keySet(), snapshot.usages().keySet());
    }

    @Test
    void runningQueryMustExposeIndependentCostProjection() {
        register("task-cost", owner);
        GenerationCostSummary costSummary = new GenerationCostSummary(
                "reserved", null, null, null,
                5L, 140_000L, 2L, null, null,
                20_000L, "provider_timeout", "已冻结 5 积分，当前暂估消耗 2 积分");
        when(costProjectionService.project("task-cost", false)).thenReturn(costSummary);

        GenerationTaskSnapshot snapshot = queryService.get("task-cost", owner);

        assertSame(costSummary, snapshot.costSummary());
    }

    @Test
    void costProjectionFailureMustNotHideTheAuthoritativeTaskStatus() {
        register("task-cost-unavailable", owner);
        doThrow(new IllegalStateException("ledger temporarily unavailable"))
                .when(costProjectionService).project("task-cost-unavailable", false);

        GenerationTaskSnapshot snapshot = queryService.get("task-cost-unavailable", owner);

        assertEquals("running", snapshot.status());
        assertNull(snapshot.costSummary());
    }

    @Test
    void cancellationMustBeIdempotentAndVisibleAsCancellingUntilWorkerTerminates() {
        register("task-cancel", owner);
        when(durableRepository.findByTaskId("task-cancel"))
                .thenReturn(Optional.of(taskRecord("task-cancel", GenerationTaskStatus.RUNNING)));
        GenerationTaskControlService controlService = new GenerationTaskControlService(
                queryService, durableRepository, runtimeLifecycleService,
                mock(GenerationTaskFinalizer.class), contextService,
                tenantAuthorizationService);
        when(runtimeLifecycleService.requestCancellation("task-cancel", "user_requested")).thenReturn(true);

        GenerationTaskSnapshot first = controlService.cancel("task-cancel", owner);
        GenerationTaskSnapshot second = controlService.cancel("task-cancel", owner);

        assertEquals("cancelling", first.status());
        assertEquals("cancelling", second.status());
        assertTrue(first.cancellationRequested());
        verify(runtimeLifecycleService, org.mockito.Mockito.times(2))
                .requestCancellation("task-cancel", "user_requested");
    }

    @Test
    void taskLookupMustEnforceOwnership() {
        register("task-owned", owner);
        doThrow(new BusinessException(com.rush.rushaicodemother.exception.ErrorCode.NO_AUTH_ERROR, "denied"))
                .when(tenantAuthorizationService)
                .requireRole(eq(100L), eq(99L), eq(TenantRole.VIEWER), anyString());

        assertThrows(BusinessException.class, () -> queryService.get("task-owned", user(99L)));
    }

    @Test
    void terminalSnapshotMustRemainQueryableDuringReplayRetention() {
        GenerationSession session = register("task-terminal", owner);
        session.tryBeginCompletion();
        session.complete();
        registry.retainForReplay(1L, session);
        contextService.finish("task-terminal", "success");

        GenerationTaskSnapshot snapshot = queryService.get("task-terminal", owner);

        assertEquals("success", snapshot.status());
    }

    @Test
    void durableWaitingApprovalMustOverrideActiveLocalExecutionStatus() {
        GenerationSession session = register("task-waiting", owner);
        Instant now = session.executionContext().snapshot().startedAt();
        when(durableRepository.findByTaskId("task-waiting")).thenReturn(Optional.of(
                new DurableGenerationTaskRecord(
                        "task-waiting", 1L, owner.getId(), 100L, "lightweight_edit",
                        GenerationTaskStatus.WAITING_APPROVAL, "approval", "approval required",
                        now, now.plusSeconds(600), false, null,
                        null, null, null, 1, 2L, null, null
                )));

        GenerationTaskSnapshot snapshot = queryService.get("task-waiting", owner);

        assertEquals("waiting_approval", snapshot.status());
        assertEquals("approval", snapshot.stage());
        assertEquals("approval required", snapshot.stageMessage());
    }

    private GenerationSession register(String taskId, User actor) {
        App app = new App();
        app.setId(1L);
        app.setUserId(actor.getId());
        app.setTenantId(100L);
        GenerationExecutionContext context = contextService.start(taskId, 1L, actor.getId());
        GenerationSession session = new GenerationSession(null, context);
        session.bindTaskRequest(new GenerationTaskRequest(app, "update title", actor));
        session.recordRoute("lightweight_edit");
        registry.put(1L, session);
        return session;
    }

    private DurableGenerationTaskRecord taskRecord(String taskId, GenerationTaskStatus status) {
        Instant now = Instant.now();
        return new DurableGenerationTaskRecord(
                taskId, 1L, owner.getId(), 100L, "lightweight_edit", status,
                "running", "running", now, now.plusSeconds(600), false, null,
                null, null, null, 0, 1L, status.isTerminal() ? now : null, null);
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
