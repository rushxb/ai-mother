package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionFactory;
import com.rush.rushaicodemother.orchestration.GenerationSessionProperties;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineExecutor;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingDecisionCode;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContextBridge;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspaceService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceExecutionScope;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
import com.rush.rushaicodemother.service.user.UserPersistenceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationTaskCommandExecutionServiceTest {

    @Test
    void scheduledWorkerRunnableMustBeTheTraceWrappedCommand() {
        String taskId = "task-traced-worker";
        Instant submittedAt = Instant.now().minusSeconds(1);
        Instant deadlineAt = submittedAt.plusSeconds(600);
        GenerationTraceContext traceContext = new GenerationTraceContext(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", null);
        GenerationTaskCommand command = new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                taskId,
                11L,
                22L,
                100L,
                "build an admin dashboard",
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationMode.AGENT_EDIT,
                0.9,
                "test",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD,
                "",
                GenerationRoutingDecisionCode.UNKNOWN,
                null,
                traceContext,
                submittedAt,
                deadlineAt
        );

        DurableGenerationTaskRepository repository = mock(DurableGenerationTaskRepository.class);
        AppPersistenceService appPersistenceService = mock(AppPersistenceService.class);
        UserPersistenceService userPersistenceService = mock(UserPersistenceService.class);
        TenantAuthorizationService tenantAuthorizationService = mock(TenantAuthorizationService.class);
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        GenerationExecutionContextService executionContextService = mock(GenerationExecutionContextService.class);
        GenerationSessionFactory sessionFactory = mock(GenerationSessionFactory.class);
        GenerationTaskExecutor taskExecutor = mock(GenerationTaskExecutor.class);
        GenerationPipelineExecutor pipelineExecutor = mock(GenerationPipelineExecutor.class);
        GenerationTaskRuntimeLifecycleService runtimeLifecycleService =
                mock(GenerationTaskRuntimeLifecycleService.class);
        GenerationTraceContextBridge traceContextBridge = mock(GenerationTraceContextBridge.class);
        GenerationPerformanceMonitorService performanceMonitorService =
                mock(GenerationPerformanceMonitorService.class);

        when(repository.findByTaskId(taskId)).thenReturn(Optional.of(taskRecord(command)));
        when(repository.findCommandByTaskId(taskId)).thenReturn(Optional.of(command));
        GenerationExecutionFence fence =
                new GenerationExecutionFence(taskId, "worker-a", 3L);
        when(runtimeLifecycleService.reserveQueued(taskId)).thenReturn(Optional.of(fence));

        App app = new App();
        app.setId(command.appId());
        app.setUserId(command.userId());
        app.setTenantId(command.tenantId());
        User user = new User();
        user.setId(command.userId());
        when(appPersistenceService.findActiveById(command.appId())).thenReturn(app);
        when(userPersistenceService.findActiveById(command.userId())).thenReturn(user);

        Path root = Path.of("target", "command-execution-trace").toAbsolutePath().normalize();
        GenerationWorkspace workspace = new GenerationWorkspace(
                command.appId(), command.codeGenType(), root, root, true,
                root, root, Set.of(), Set.of());
        when(workspaceService.resolve(app, command.codeGenType())).thenReturn(workspace);

        GenerationRuntimeProperties runtimeProperties = new GenerationRuntimeProperties();
        GenerationExecutionContext executionContext = new GenerationExecutionContext(
                taskId,
                command.appId(),
                command.userId(),
                submittedAt,
                runtimeProperties.toLimits(),
                Clock.systemUTC()
        );
        when(executionContextService.getByTaskId(taskId)).thenReturn(Optional.of(executionContext));
        GenerationSession session = new GenerationSession(null, executionContext);
        when(sessionFactory.create(null, executionContext)).thenReturn(session);

        AtomicReference<Runnable> tracedDelegate = new AtomicReference<>();
        Runnable wrappedRunnable = mock(Runnable.class);
        when(traceContextBridge.wrap(
                eq(traceContext),
                eq("generation.task.execute"),
                anyMap(),
                any(Runnable.class)
        )).thenAnswer(invocation -> {
            tracedDelegate.set(invocation.getArgument(3));
            return wrappedRunnable;
        });

        GenerationTaskCommandExecutionService service = new GenerationTaskCommandExecutionService(
                repository,
                appPersistenceService,
                userPersistenceService,
                tenantAuthorizationService,
                workspaceService,
                executionContextService,
                runtimeProperties,
                sessionFactory,
                new GenerationSessionRegistry(new GenerationSessionProperties()),
                taskExecutor,
                pipelineExecutor,
                runtimeLifecycleService,
                traceContextBridge,
                performanceMonitorService
        );
        Runnable completionCallback = mock(Runnable.class);

        assertEquals(GenerationTaskDispatchResult.SCHEDULED,
                service.schedule(taskId, completionCallback));
        ArgumentCaptor<GenerationTaskExecution> executionCaptor =
                ArgumentCaptor.forClass(GenerationTaskExecution.class);
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(executionCaptor.capture(), runnableCaptor.capture());
        assertEquals(taskId, executionCaptor.getValue().taskId());
        assertEquals(fence, executionCaptor.getValue().executionFence());
        assertSame(wrappedRunnable, runnableCaptor.getValue());

        ArgumentCaptor<Map<String, String>> tagsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(traceContextBridge).wrap(
                eq(traceContext),
                eq("generation.task.execute"),
                tagsCaptor.capture(),
                any(Runnable.class)
        );
        assertEquals(taskId, tagsCaptor.getValue().get("generation.task.id"));
        assertEquals("3", tagsCaptor.getValue().get("generation.execution.epoch"));
        assertEquals("11", tagsCaptor.getValue().get("generation.app.id"));
        assertEquals("100", tagsCaptor.getValue().get("generation.tenant.id"));
        assertEquals("agent_edit", tagsCaptor.getValue().get("generation.route"));

        assertNotNull(tracedDelegate.get());
        tracedDelegate.get().run();
        verify(pipelineExecutor).execute(any());
        verify(completionCallback).run();
        verify(performanceMonitorService).recordSpan(
                eq(taskId),
                eq("worker_queue_wait"),
                eq(GenerationSpanCategory.QUEUE),
                eq("success"),
                any(Duration.class),
                eq("attempt=0")
        );
    }

    @Test
    void claimMustBeReleasedWhenAnotherLocalSessionWinsThePostClaimRace() {
        String taskId = "task-duplicate-after-claim";
        Instant submittedAt = Instant.now().minusSeconds(1);
        GenerationTaskCommand command = new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                taskId,
                11L,
                22L,
                100L,
                "build an admin dashboard",
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationMode.AGENT_EDIT,
                0.9,
                "test",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD,
                "",
                GenerationRoutingDecisionCode.UNKNOWN,
                null,
                GenerationTraceContext.empty(),
                submittedAt,
                submittedAt.plusSeconds(600)
        );

        DurableGenerationTaskRepository repository = mock(DurableGenerationTaskRepository.class);
        AppPersistenceService appPersistenceService = mock(AppPersistenceService.class);
        UserPersistenceService userPersistenceService = mock(UserPersistenceService.class);
        TenantAuthorizationService tenantAuthorizationService = mock(TenantAuthorizationService.class);
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        GenerationExecutionContextService executionContextService = mock(GenerationExecutionContextService.class);
        GenerationSessionFactory sessionFactory = mock(GenerationSessionFactory.class);
        GenerationSessionRegistry sessionRegistry = mock(GenerationSessionRegistry.class);
        GenerationTaskExecutor taskExecutor = mock(GenerationTaskExecutor.class);
        GenerationPipelineExecutor pipelineExecutor = mock(GenerationPipelineExecutor.class);
        GenerationTaskRuntimeLifecycleService runtimeLifecycleService =
                mock(GenerationTaskRuntimeLifecycleService.class);
        GenerationTraceContextBridge traceContextBridge = mock(GenerationTraceContextBridge.class);
        GenerationPerformanceMonitorService performanceMonitorService =
                mock(GenerationPerformanceMonitorService.class);

        when(repository.findByTaskId(taskId)).thenReturn(Optional.of(taskRecord(command)));
        when(repository.findCommandByTaskId(taskId)).thenReturn(Optional.of(command));
        GenerationExecutionFence fence = new GenerationExecutionFence(taskId, "worker-a", 7L);
        when(runtimeLifecycleService.reserveQueued(taskId)).thenReturn(Optional.of(fence));

        App app = new App();
        app.setId(command.appId());
        app.setUserId(command.userId());
        app.setTenantId(command.tenantId());
        User user = new User();
        user.setId(command.userId());
        when(appPersistenceService.findActiveById(command.appId())).thenReturn(app);
        when(userPersistenceService.findActiveById(command.userId())).thenReturn(user);

        GenerationSession competingSession = mock(GenerationSession.class);
        when(competingSession.isActive()).thenReturn(true);
        when(sessionRegistry.getByTaskId(taskId)).thenReturn(null, competingSession);
        when(sessionRegistry.lock(command.appId())).thenReturn(new Object());

        GenerationTaskCommandExecutionService service = new GenerationTaskCommandExecutionService(
                repository,
                appPersistenceService,
                userPersistenceService,
                tenantAuthorizationService,
                workspaceService,
                executionContextService,
                new GenerationRuntimeProperties(),
                sessionFactory,
                sessionRegistry,
                taskExecutor,
                pipelineExecutor,
                runtimeLifecycleService,
                traceContextBridge,
                performanceMonitorService
        );

        assertEquals(GenerationTaskDispatchResult.ALREADY_ACTIVE,
                service.schedule(taskId, null));
        verify(runtimeLifecycleService).releaseClaimToQueue(
                fence, "duplicate_local_session_after_claim");
        verifyNoInteractions(taskExecutor, pipelineExecutor, workspaceService);
    }

    @Test
    void workspaceMaterializationMustBeDeadlineFencedAndNotRequirePrematureToolContext() {
        String taskId = "task-workspace-admission";
        Instant submittedAt = Instant.now().minusSeconds(1);
        GenerationTaskCommand command = new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                taskId,
                11L,
                22L,
                100L,
                "create a dashboard",
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationMode.CREATE,
                0.9,
                "test",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.EXPERT,
                "",
                GenerationRoutingDecisionCode.UNKNOWN,
                null,
                GenerationTraceContext.empty(),
                submittedAt,
                submittedAt.plusSeconds(600)
        );
        DurableGenerationTaskRepository repository = mock(DurableGenerationTaskRepository.class);
        AppPersistenceService appPersistenceService = mock(AppPersistenceService.class);
        UserPersistenceService userPersistenceService = mock(UserPersistenceService.class);
        TenantAuthorizationService tenantAuthorizationService = mock(TenantAuthorizationService.class);
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        GenerationExecutionWorkspaceService executionWorkspaceService =
                mock(GenerationExecutionWorkspaceService.class);
        GenerationWorkspaceExecutionScope workspaceExecutionScope =
                mock(GenerationWorkspaceExecutionScope.class);
        GenerationToolExecutionContextService toolContextService =
                new GenerationToolExecutionContextService();
        GenerationExecutionContextService executionContextService =
                new GenerationExecutionContextService(new GenerationRuntimeProperties());
        GenerationSessionFactory sessionFactory = mock(GenerationSessionFactory.class);
        GenerationSessionRegistry sessionRegistry =
                new GenerationSessionRegistry(new GenerationSessionProperties());
        GenerationTaskExecutor taskExecutor = mock(GenerationTaskExecutor.class);
        GenerationPipelineExecutor pipelineExecutor = mock(GenerationPipelineExecutor.class);
        GenerationTaskRuntimeLifecycleService runtimeLifecycleService =
                mock(GenerationTaskRuntimeLifecycleService.class);
        GenerationTraceContextBridge traceContextBridge = mock(GenerationTraceContextBridge.class);
        GenerationPerformanceMonitorService performanceMonitorService =
                mock(GenerationPerformanceMonitorService.class);
        GenerationPerformanceMonitorService.SpanTimer workspaceSpan =
                mock(GenerationPerformanceMonitorService.SpanTimer.class);

        when(repository.findByTaskId(taskId)).thenReturn(Optional.of(taskRecord(command)));
        when(repository.findCommandByTaskId(taskId)).thenReturn(Optional.of(command));
        GenerationExecutionFence fence = new GenerationExecutionFence(taskId, "worker-a", 4L);
        when(runtimeLifecycleService.reserveQueued(taskId)).thenReturn(Optional.of(fence));
        App app = new App();
        app.setId(command.appId());
        app.setUserId(command.userId());
        app.setTenantId(command.tenantId());
        User user = new User();
        user.setId(command.userId());
        when(appPersistenceService.findActiveById(command.appId())).thenReturn(app);
        when(userPersistenceService.findActiveById(command.userId())).thenReturn(user);

        Path epochRoot = Path.of("target", "workspace-admission", "epoch-4")
                .toAbsolutePath().normalize();
        Path typeRoot = epochRoot.resolve(CodeGenTypeEnum.VUE_PROJECT.getValue());
        Path root = typeRoot.resolve("workspace");
        GenerationWorkspace workspace = new GenerationWorkspace(
                command.appId(), command.codeGenType(), root, root, false,
                root, null, Set.of(), Set.of());
        GenerationExecutionWorkspace executionWorkspace = new GenerationExecutionWorkspace(
                command.appId(), fence, command.codeGenType(), epochRoot, typeRoot,
                workspace, null);
        when(executionWorkspaceService.register(fence, command.appId(), command.codeGenType()))
                .thenAnswer(ignored -> {
                    assertEquals(fence,
                            executionContextService.getExecutionFence(taskId).orElseThrow());
                    return executionWorkspace;
                });
        when(sessionFactory.create(eq(null), any(GenerationExecutionContext.class)))
                .thenAnswer(invocation -> new GenerationSession(null, invocation.getArgument(1)));
        when(traceContextBridge.wrap(any(), any(), anyMap(), any(Runnable.class)))
                .thenAnswer(invocation -> invocation.getArgument(3));
        when(performanceMonitorService.startSpan(
                taskId,
                "execution_workspace_materialization",
                GenerationSpanCategory.WORKSPACE
        )).thenReturn(workspaceSpan);

        GenerationTaskCommandExecutionService service = new GenerationTaskCommandExecutionService(
                repository,
                appPersistenceService,
                userPersistenceService,
                tenantAuthorizationService,
                workspaceService,
                executionWorkspaceService,
                workspaceExecutionScope,
                toolContextService,
                executionContextService,
                new GenerationRuntimeProperties(),
                sessionFactory,
                sessionRegistry,
                taskExecutor,
                pipelineExecutor,
                runtimeLifecycleService,
                traceContextBridge,
                performanceMonitorService
        );

        assertEquals(GenerationTaskDispatchResult.SCHEDULED, service.schedule(taskId, null));
        assertTrue(toolContextService.getContext(command.appId()).isEmpty());
        verify(executionWorkspaceService).register(fence, command.appId(), command.codeGenType());
        verify(workspaceSpan).close(eq("success"), any(String.class));
        verify(taskExecutor).execute(any(GenerationTaskExecution.class), any(Runnable.class));
    }

    private DurableGenerationTaskRecord taskRecord(GenerationTaskCommand command) {
        return new DurableGenerationTaskRecord(
                command.taskId(),
                command.appId(),
                command.userId(),
                command.tenantId(),
                command.route(),
                GenerationTaskStatus.QUEUED,
                "queued",
                "queued",
                command.submittedAt(),
                command.deadlineAt(),
                false,
                null,
                null,
                null,
                null,
                0,
                1,
                null,
                null
        );
    }
}
