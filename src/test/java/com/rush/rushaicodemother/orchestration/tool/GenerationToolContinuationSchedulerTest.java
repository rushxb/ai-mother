package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionFactory;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationCoordinator;
import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationPreparationService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecutor;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContextBridge;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import com.rush.rushaicodemother.service.user.UserPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationToolContinuationSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final GenerationExecutionFence FENCE =
            new GenerationExecutionFence("task-1", "worker-a", 5L);

    private ToolInvocationCheckpointFactory checkpointFactory;
    private GenerationExecutionContextService executionContexts;
    private GenerationSessionFactory sessionFactory;
    private GenerationSessionRegistry sessions;
    private GenerationTaskExecutor taskExecutor;
    private GenerationTaskRuntimeLifecycleService runtimeLifecycle;
    private DurableGenerationTaskRepository durableTasks;
    private HeavyGenerationCoordinator heavyCoordinator;
    private HeavyGenerationPreparationService preparationService;
    private AppPersistenceService apps;
    private UserPersistenceService users;
    private GenerationTraceService traceService;
    private GenerationTraceContextBridge traceContextBridge;
    private GenerationToolContinuationScheduler scheduler;

    @BeforeEach
    void setUp() {
        checkpointFactory = mock(ToolInvocationCheckpointFactory.class);
        executionContexts = mock(GenerationExecutionContextService.class);
        sessionFactory = mock(GenerationSessionFactory.class);
        sessions = mock(GenerationSessionRegistry.class);
        taskExecutor = mock(GenerationTaskExecutor.class);
        runtimeLifecycle = mock(GenerationTaskRuntimeLifecycleService.class);
        durableTasks = mock(DurableGenerationTaskRepository.class);
        heavyCoordinator = mock(HeavyGenerationCoordinator.class);
        preparationService = mock(HeavyGenerationPreparationService.class);
        apps = mock(AppPersistenceService.class);
        users = mock(UserPersistenceService.class);
        traceService = mock(GenerationTraceService.class);
        traceContextBridge = mock(GenerationTraceContextBridge.class);
        scheduler = new GenerationToolContinuationScheduler(
                checkpointFactory, executionContexts, sessionFactory, sessions,
                taskExecutor, runtimeLifecycle, durableTasks, heavyCoordinator, preparationService,
                apps, users, traceService, traceContextBridge);
        when(traceContextBridge.wrap(any(), anyString(), anyMap(), any(Runnable.class)))
                .thenAnswer(invocation -> invocation.getArgument(3));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(taskExecutor).execute(any(GenerationTaskExecution.class), any(Runnable.class));
        when(runtimeLifecycle.requeueAfterApproval("task-1")).thenReturn(Optional.of(FENCE));
    }

    @Test
    void localContinuationMustRequeueActivateAndResumeSameSession() {
        Fixture fixture = fixture();
        when(checkpointFactory.restore(fixture.decision().invocationCheckpoint()))
                .thenReturn(fixture.state());
        when(executionContexts.getByTaskId("task-1"))
                .thenReturn(Optional.of(fixture.context()));
        when(sessions.getByTaskId("task-1")).thenReturn(fixture.session());
        when(fixture.session().executionContext()).thenReturn(fixture.context());

        scheduler.schedule(fixture.decision());

        var order = inOrder(runtimeLifecycle, heavyCoordinator);
        order.verify(runtimeLifecycle).requeueAfterApproval("task-1");
        order.verify(runtimeLifecycle).activate(FENCE);
        order.verify(heavyCoordinator).resumeAfterToolDecision(
                fixture.decision(), fixture.state(), fixture.session());
    }

    @Test
    void missingLocalSessionMustRestoreDurableRuntimeAndToolContext() {
        Fixture fixture = fixture();
        App app = App.builder().id(11L).userId(7L).build();
        User user = User.builder().id(7L).build();
        when(checkpointFactory.restore(fixture.decision().invocationCheckpoint()))
                .thenReturn(fixture.state());
        when(executionContexts.getByTaskId("task-1")).thenReturn(Optional.empty());
        when(executionContexts.restore(fixture.state().execution(), fixture.state().executionLimits()))
                .thenReturn(fixture.context());
        when(sessions.getByTaskId("task-1")).thenReturn(null);
        when(sessions.lock(11L)).thenReturn(new Object());
        when(apps.findActiveById(11L)).thenReturn(app);
        when(users.findActiveById(7L)).thenReturn(user);
        when(sessionFactory.create(fixture.state().preparation(), fixture.context()))
                .thenReturn(fixture.session());

        scheduler.schedule(fixture.decision());

        verify(fixture.session()).bindTaskRequest(any());
        verify(fixture.session()).recordRoute("heavy_generation");
        verify(fixture.session()).bindTraceContext(traceService, 11L, 7L);
        verify(preparationService).restoreToolExecutionContext(
                app, fixture.state().preparation(), FENCE, null);
        verify(sessions).put(11L, fixture.session());
        verify(heavyCoordinator).resumeAfterToolDecision(
                fixture.decision(), fixture.state(), fixture.session());
    }

    @Test
    void executorRejectionMustRestoreWaitingStateForRetry() {
        Fixture fixture = fixture();
        when(checkpointFactory.restore(fixture.decision().invocationCheckpoint()))
                .thenReturn(fixture.state());
        when(executionContexts.getByTaskId("task-1"))
                .thenReturn(Optional.of(fixture.context()));
        when(sessions.getByTaskId("task-1")).thenReturn(fixture.session());
        when(fixture.session().executionContext()).thenReturn(fixture.context());
        org.mockito.Mockito.doThrow(new IllegalStateException("executor full"))
                .when(taskExecutor).execute(any(GenerationTaskExecution.class), any(Runnable.class));

        assertThrows(IllegalStateException.class, () -> scheduler.schedule(fixture.decision()));

        verify(runtimeLifecycle).restoreWaitingAfterDispatchFailure(
                FENCE, "approval_dispatch_retry");
    }

    @Test
    void expiredApprovalContinuationMustTerminalizeWithoutStartingWorker() {
        Fixture fixture = fixture();
        when(checkpointFactory.restore(fixture.decision().invocationCheckpoint()))
                .thenReturn(fixture.state());
        when(executionContexts.getByTaskId("task-1"))
                .thenReturn(Optional.of(fixture.context()));
        when(sessions.getByTaskId("task-1")).thenReturn(fixture.session());
        when(fixture.session().executionContext()).thenReturn(fixture.context());
        when(runtimeLifecycle.requeueAfterApproval("task-1")).thenReturn(Optional.empty());
        when(durableTasks.findByTaskId("task-1")).thenReturn(Optional.of(
                waitingApprovalTask(Instant.EPOCH)));

        scheduler.schedule(fixture.decision());

        verify(heavyCoordinator).timeoutWaitingToolApproval(fixture.state(), fixture.session());
        verify(taskExecutor, never()).execute(any(GenerationTaskExecution.class), any(Runnable.class));
        verify(runtimeLifecycle, never()).activate(any());
    }

    private DurableGenerationTaskRecord waitingApprovalTask(Instant deadlineAt) {
        return new DurableGenerationTaskRecord(
                "task-1", 11L, 7L, 3L, "heavy_generation",
                GenerationTaskStatus.WAITING_APPROVAL, "approval", "waiting",
                NOW.minusSeconds(60), deadlineAt, false, null,
                null, null, null, 1, 4L, null, null
        );
    }

    private Fixture fixture() {
        GenerationRuntimeProperties runtimeProperties = new GenerationRuntimeProperties();
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-1", 11L, 7L, NOW.minusSeconds(30),
                runtimeProperties.toLimits(), Clock.fixed(NOW, ZoneOffset.UTC));
        GenerationPreparation preparation = new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT, CodeGenTypeEnum.VUE_PROJECT,
                false, "create", "enhanced prompt", List.of(),
                new java.util.LinkedHashMap<>(), null, Map.of(), "task-1");
        GenerationToolContinuationState state = new GenerationToolContinuationState(
                2,
                "task-1", 11L, 7L, "heavy_generation", "build a dashboard",
                CodeGenTypeEnum.VUE_PROJECT, GenerationPerformanceProfile.balanced(),
                preparation, context.limits(), context.snapshot(),
                new GenerationTraceContext(
                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", null),
                NOW);
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                "call-1", "manageSnapshot", "{}", "{\"taskId\":\"task-1\"}", NOW);
        ToolApprovalRecord decision = new ToolApprovalRecord(
                "a".repeat(64), "task-1", 11L, 7L,
                DestructiveToolAction.SNAPSHOT_ROLLBACK, "{}", ToolApprovalStatus.APPROVED,
                NOW.minusSeconds(10), NOW.plusSeconds(600), 7L, NOW, null, 2L, checkpoint);
        GenerationSession session = mock(GenerationSession.class);
        when(session.taskRequest()).thenReturn(new GenerationTaskRequest(
                App.builder().id(11L).userId(7L).build(),
                "build a dashboard",
                User.builder().id(7L).build()
        ));
        return new Fixture(decision, state, context, session);
    }

    private record Fixture(ToolApprovalRecord decision,
                           GenerationToolContinuationState state,
                           GenerationExecutionContext context,
                           GenerationSession session) {
    }
}
