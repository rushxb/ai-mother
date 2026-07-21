package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionProperties;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GenerationPipelineExecutorTest {

    private GenerationSessionRegistry sessionRegistry;
    private GenerationExecutionContextService contextService;
    private GenerationEventPublisher eventPublisher;
    private GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private GenerationPerformanceMonitorService performanceMonitorService;

    @BeforeEach
    void setUp() {
        sessionRegistry = new GenerationSessionRegistry(new GenerationSessionProperties());
        contextService = new GenerationExecutionContextService(new GenerationRuntimeProperties());
        eventPublisher = mock(GenerationEventPublisher.class);
        runtimeLifecycleService = mock(GenerationTaskRuntimeLifecycleService.class);
        performanceMonitorService = new GenerationPerformanceMonitorService();
    }

    @Test
    void completedOutcomeMustFinalizeSessionAndRuntime() {
        GenerationPipelineRequest request = request("task-complete", GenerationMode.LIGHT_EDIT,
                FallbackPolicy.NONE);
        GenerationPipeline pipeline = pipeline(GenerationRoute.LIGHTWEIGHT_EDIT, GenerationMode.LIGHT_EDIT,
                ignored -> GenerationPipelineOutcome.completed(
                        GenerationRoute.LIGHTWEIGHT_EDIT, GenerationTaskStatus.SUCCESS));

        executor(List.of(pipeline)).execute(request);

        assertFalse(request.execution().session().isActive());
        assertTrue(contextService.getByTaskId("task-complete").isEmpty());
        assertEquals("success", request.execution().executionContext().snapshot().terminalStatus());
        assertSame(request.execution().session(), sessionRegistry.getByTaskId("task-complete"));
        verify(runtimeLifecycleService).activate(request.execution().executionFence());
        verify(runtimeLifecycleService).completeOwned(
                request.execution().executionFence(), GenerationTaskStatus.SUCCESS, null);
    }

    @Test
    void runningOutcomeMustLeaveCompletionToBackgroundOwner() {
        GenerationPipelineRequest request = request("task-running", GenerationMode.HEAVY_EXPERT,
                FallbackPolicy.NONE);
        GenerationPipeline pipeline = pipeline(GenerationRoute.HEAVY_GENERATION, GenerationMode.HEAVY_EXPERT,
                ignored -> GenerationPipelineOutcome.running(GenerationRoute.HEAVY_GENERATION));

        executor(List.of(pipeline)).execute(request);

        assertTrue(request.execution().session().isActive());
        assertTrue(contextService.getByTaskId("task-running").isPresent());
        verify(runtimeLifecycleService).activate(request.execution().executionFence());
        verify(runtimeLifecycleService, never()).completeOwned(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void fallbackMustReuseTaskIdentitySessionAndExecutionContext() {
        GenerationPipelineRequest request = request("task-fallback", GenerationMode.LIGHT_EDIT,
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT);
        AtomicReference<GenerationPipelineRequest> heavyRequest = new AtomicReference<>();
        GenerationPipeline lightweight = pipeline(
                GenerationRoute.LIGHTWEIGHT_EDIT,
                GenerationMode.LIGHT_EDIT,
                ignored -> GenerationPipelineOutcome.fallback(
                        GenerationRoute.LIGHTWEIGHT_EDIT, "not_applicable"));
        GenerationPipeline heavy = pipeline(
                GenerationRoute.HEAVY_GENERATION,
                GenerationMode.HEAVY_EXPERT,
                candidate -> {
                    heavyRequest.set(candidate);
                    return GenerationPipelineOutcome.running(GenerationRoute.HEAVY_GENERATION);
                });

        executor(List.of(lightweight, heavy)).execute(request);

        assertSame(request.execution(), heavyRequest.get().execution());
        assertEquals("task-fallback", heavyRequest.get().requireExecution().taskId());
        assertSame(request.execution().session(), heavyRequest.get().requireExecution().session());
        assertEquals(GenerationRoute.HEAVY_GENERATION, request.execution().session().route());
    }

    @Test
    void runtimeFailureMustPublishTerminalStateAndReleaseExecutionContext() {
        GenerationPipelineRequest request = request("task-failed", GenerationMode.LIGHT_EDIT,
                FallbackPolicy.NONE);
        GenerationPipeline pipeline = pipeline(
                GenerationRoute.LIGHTWEIGHT_EDIT,
                GenerationMode.LIGHT_EDIT,
                ignored -> { throw new IllegalStateException("provider secret"); });

        executor(List.of(pipeline)).execute(request);

        assertFalse(request.execution().session().isActive());
        assertEquals("failed", request.execution().executionContext().snapshot().terminalStatus());
        assertTrue(contextService.getByTaskId("task-failed").isEmpty());
        verify(runtimeLifecycleService).completeOwned(
                request.execution().executionFence(),
                GenerationTaskStatus.FAILED,
                "generation_pipeline_failed");
    }

    @Test
    void finalizationMustUseExecutionFenceInsteadOfUnconditionalTaskCleanup() {
        GenerationPipelineRequest request = request("task-fenced-cleanup", GenerationMode.LIGHT_EDIT,
                FallbackPolicy.NONE);
        GenerationPipeline pipeline = pipeline(
                GenerationRoute.LIGHTWEIGHT_EDIT,
                GenerationMode.LIGHT_EDIT,
                ignored -> GenerationPipelineOutcome.completed(
                        GenerationRoute.LIGHTWEIGHT_EDIT, GenerationTaskStatus.SUCCESS));
        GenerationExecutionContextService cleanupService = mock(GenerationExecutionContextService.class);
        GenerationPipelineExecutor executor = new GenerationPipelineExecutor(
                List.of(pipeline), eventPublisher, sessionRegistry, cleanupService,
                runtimeLifecycleService, performanceMonitorService);

        executor.execute(request);

        verify(cleanupService).finishIfOwned(
                request.execution().taskId(),
                request.execution().executionFence(),
                GenerationTaskStatus.SUCCESS.getValue());
        verify(cleanupService, never()).finish(
                request.execution().taskId(), GenerationTaskStatus.SUCCESS.getValue());
    }

    private GenerationPipelineExecutor executor(List<GenerationPipeline> pipelines) {
        return new GenerationPipelineExecutor(
                pipelines, eventPublisher, sessionRegistry, contextService, runtimeLifecycleService,
                performanceMonitorService);
    }

    private GenerationPipeline pipeline(String route,
                                        GenerationMode mode,
                                        PipelineAction action) {
        return new GenerationPipeline() {
            @Override
            public String route() {
                return route;
            }

            @Override
            public boolean supports(GenerationPipelineRequest request) {
                return request.modeIs(mode);
            }

            @Override
            public GenerationPipelineOutcome execute(GenerationPipelineRequest request) {
                return action.execute(request);
            }
        };
    }

    private GenerationPipelineRequest request(String taskId,
                                              GenerationMode mode,
                                              FallbackPolicy fallbackPolicy) {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        User user = new User();
        user.setId(2L);
        GenerationTaskRequest taskRequest = new GenerationTaskRequest(app, "update title", user);
        Path root = Path.of("target/pipeline-executor-test").toAbsolutePath().normalize();
        GenerationWorkspace workspace = new GenerationWorkspace(
                1L, CodeGenTypeEnum.VUE_PROJECT, root, root, true, root, root, Set.of(), Set.of());
        GenerationModeDecision decision = GenerationModeDecision.of(
                mode, 0.8, "test", fallbackPolicy, ExpectedValidationLevel.BUILD);
        GenerationExecutionContext context = contextService.start(taskId, 1L, 2L);
        GenerationExecutionFence fence = new GenerationExecutionFence(taskId, "worker-a", 3L);
        context.bindExecutionFence(fence);
        GenerationSession session = new GenerationSession(null, context);
        session.bindTaskRequest(taskRequest);
        session.recordRoute(decision.route());
        sessionRegistry.put(1L, session);
        GenerationTaskExecution execution = new GenerationTaskExecution(
                taskId, session, context, fence, Instant.now());
        return new GenerationPipelineRequest(
                taskRequest, CodeGenTypeEnum.VUE_PROJECT, workspace, decision, execution);
    }

    @FunctionalInterface
    private interface PipelineAction {
        GenerationPipelineOutcome execute(GenerationPipelineRequest request);
    }
}
