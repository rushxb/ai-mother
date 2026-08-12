package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.memory.GenerationOutcomeMemoryRequest;
import com.rush.rushaicodemother.memory.GenerationOutcomeMemoryService;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionProperties;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionPolicy;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommand;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTaskFinalizer;
import com.rush.rushaicodemother.orchestration.intent.IntentClarificationStage;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.runtime.execution.DefaultGenerationSlaPolicy;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationPipelineExecutorTest {

    private GenerationSessionRegistry sessionRegistry;
    private GenerationExecutionContextService contextService;
    private GenerationEventPublisher eventPublisher;
    private GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private GenerationPerformanceMonitorService performanceMonitorService;
    private GenerationWorkspaceReleaseService workspaceReleaseService;
    private GenerationTaskFinalizer taskFinalizer;
    private GenerationOutcomeMemoryService outcomeMemoryService;
    private GenerationCompletionPolicy completionPolicy;
    private IntentClarificationStage intentClarificationStage;

    @BeforeEach
    void setUp() {
        sessionRegistry = new GenerationSessionRegistry(new GenerationSessionProperties());
        contextService = new GenerationExecutionContextService(new GenerationRuntimeProperties());
        eventPublisher = mock(GenerationEventPublisher.class);
        runtimeLifecycleService = mock(GenerationTaskRuntimeLifecycleService.class);
        performanceMonitorService = new GenerationPerformanceMonitorService();
        workspaceReleaseService = mock(GenerationWorkspaceReleaseService.class);
        taskFinalizer = mock(GenerationTaskFinalizer.class);
        outcomeMemoryService = mock(GenerationOutcomeMemoryService.class);
        // 栅栏守卫放行，使断言聚焦流水线终态编排；栅栏拒绝路径由完成门禁自身的测试覆盖。
        completionPolicy = new GenerationCompletionPolicy(mock(GenerationTaskFenceGuard.class));
        // 澄清阶段默认原样返回请求，使断言聚焦流水线编排；澄清行为由其自身的测试覆盖。
        intentClarificationStage = mock(IntentClarificationStage.class);
        when(intentClarificationStage.apply(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void completedOutcomeMustFinalizeSessionAndRuntime() {
        GenerationPipelineRequest request = request("task-complete", GenerationMode.LIGHT_EDIT,
                FallbackPolicy.NONE);
        GenerationPipeline pipeline = pipeline(GenerationRoute.LIGHTWEIGHT_EDIT, GenerationMode.LIGHT_EDIT,
                ignored -> GenerationPipelineOutcome.completed(
                        GenerationRoute.LIGHTWEIGHT_EDIT,
                        GenerationTaskStatus.SUCCESS,
                        null,
                        "任务状态：成功\n结果摘要：标题已更新",
                        successfulCompletionEvidence()));

        executor(List.of(pipeline)).execute(request);

        assertFalse(request.execution().session().isActive());
        assertTrue(contextService.getByTaskId("task-complete").isEmpty());
        assertEquals("success", request.execution().executionContext().snapshot().terminalStatus());
        assertSame(request.execution().session(), sessionRegistry.getByTaskId("task-complete"));
        verify(runtimeLifecycleService).activate(request.execution().executionFence());
        verify(taskFinalizer).finalizeManaged(org.mockito.ArgumentMatchers.argThat(command ->
                command.taskId().equals("task-complete")
                        && command.appId().equals(1L)
                        && command.executionFence().equals(request.execution().executionFence())
                        && command.status() == GenerationTaskStatus.SUCCESS
                        && command.reason() == null
                        && command.memorySummary().equals("任务状态：成功\n结果摘要：标题已更新")));
        ArgumentCaptor<GenerationOutcomeMemoryRequest> memoryCaptor =
                ArgumentCaptor.forClass(GenerationOutcomeMemoryRequest.class);
        verify(outcomeMemoryService).remember(memoryCaptor.capture());
        assertEquals(GenerationTaskStatus.SUCCESS, memoryCaptor.getValue().status());
        assertEquals("任务状态：成功\n结果摘要：标题已更新", memoryCaptor.getValue().memorySummary());
    }

    @ParameterizedTest
    @EnumSource(value = GenerationMode.class, names = {"CREATE", "LIGHT_EDIT", "AGENT_EDIT"})
    void successfulSynchronousModesMustReleaseUserVisibleWorkspace(GenerationMode mode) {
        String route = switch (mode) {
            case CREATE -> GenerationRoute.CREATE;
            case LIGHT_EDIT -> GenerationRoute.LIGHTWEIGHT_EDIT;
            case AGENT_EDIT -> GenerationRoute.AGENT_EDIT;
            default -> throw new IllegalArgumentException("测试未覆盖该生成模式: " + mode);
        };
        GenerationPipelineRequest request = request(
                "task-release-" + mode.name().toLowerCase(java.util.Locale.ROOT),
                mode,
                FallbackPolicy.NONE
        );
        GenerationPipeline pipeline = pipeline(
                route,
                mode,
                ignored -> GenerationPipelineOutcome.completed(
                        route,
                        GenerationTaskStatus.SUCCESS,
                        null,
                        "任务状态：成功",
                        successfulCompletionEvidence()
                )
        );

        executor(List.of(pipeline)).execute(request);

        verify(workspaceReleaseService).releaseVerified(
                request.requireExecution().session(), CodeGenTypeEnum.VUE_PROJECT);
    }

    @Test
    void failedOutcomeMustPersistFailureLessonWithoutCharging() {
        GenerationPipelineRequest request = request("task-completed-failure", GenerationMode.AGENT_EDIT,
                FallbackPolicy.NONE);
        GenerationPipeline pipeline = pipeline(
                GenerationRoute.AGENT_EDIT,
                GenerationMode.AGENT_EDIT,
                ignored -> GenerationPipelineOutcome.completed(
                        GenerationRoute.AGENT_EDIT,
                        GenerationTaskStatus.FAILED,
                        "agent_validation_failed",
                        "任务状态：失败\n失败原因：构建验证未通过")
        );

        executor(List.of(pipeline)).execute(request);

        verify(taskFinalizer).finalizeManaged(org.mockito.ArgumentMatchers.argThat(command ->
                command.taskId().equals("task-completed-failure")
                        && command.appId().equals(1L)
                        && command.status() == GenerationTaskStatus.FAILED
                        && command.reason().equals("agent_validation_failed")
                        && command.memorySummary().equals(
                                "任务状态：失败\n失败原因：构建验证未通过")));
        ArgumentCaptor<GenerationOutcomeMemoryRequest> memoryCaptor =
                ArgumentCaptor.forClass(GenerationOutcomeMemoryRequest.class);
        verify(outcomeMemoryService).remember(memoryCaptor.capture());
        assertEquals(GenerationTaskStatus.FAILED, memoryCaptor.getValue().status());
        assertEquals("agent_edit", memoryCaptor.getValue().orchestrationMode());
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
        verify(taskFinalizer, never()).finalizeManaged(any());
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
    void createFallbackMayEscalateFromEmptyWorkspaceWithSanitizedReason() {
        GenerationPipelineRequest request = request(
                "task-create-fallback", GenerationMode.CREATE,
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT, false);
        AtomicReference<GenerationPipelineRequest> heavyRequest = new AtomicReference<>();
        GenerationPipeline create = pipeline(
                GenerationRoute.CREATE,
                GenerationMode.CREATE,
                ignored -> GenerationPipelineOutcome.fallback(
                        GenerationRoute.CREATE, "provider-api-key=secret-value"));
        GenerationPipeline heavy = pipeline(
                GenerationRoute.HEAVY_GENERATION,
                GenerationMode.HEAVY_EXPERT,
                candidate -> {
                    heavyRequest.set(candidate);
                    return GenerationPipelineOutcome.running(GenerationRoute.HEAVY_GENERATION);
                });

        executor(List.of(create, heavy)).execute(request);

        assertNotNull(heavyRequest.get());
        assertSame(request.execution(), heavyRequest.get().execution());
        assertFalse(heavyRequest.get().modeDecision().fallbackReason().contains("secret-value"));
        assertTrue(heavyRequest.get().modeDecision().fallbackReason().contains("[REDACTED]"));
        GenerationStreamEvent transition = request.execution().session().asFlux()
                .filter(event -> GenerationStreamEvent.GENERATION_STAGE.equals(event.getType()))
                .blockFirst(Duration.ofSeconds(1));
        assertNotNull(transition);
        assertTrue(transition.getText().contains("正在切换专家模式"));
        verify(taskFinalizer, never()).finalizeManaged(any());
    }

    @Test
    void createFallbackBudgetMustAdmitHeavyGenerationAndDeclaredRepair() {
        GenerationSlaProperties slaProperties = new GenerationSlaProperties();
        GenerationModeDecision createDecision = GenerationModeDecision.of(
                GenerationMode.CREATE,
                0.8,
                "test",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD
        );
        GenerationExecutionLimits limits = new DefaultGenerationSlaPolicy(slaProperties)
                .resolve(createDecision, CodeGenTypeEnum.VUE_PROJECT)
                .toLimits();
        GenerationPipelineRequest request = request(
                "task-create-budget-fallback",
                GenerationMode.CREATE,
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                false,
                limits
        );
        GenerationPipeline create = pipeline(
                GenerationRoute.CREATE,
                GenerationMode.CREATE,
                candidate -> {
                    candidate.requireExecution().executionContext()
                            .consume(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
                    return GenerationPipelineOutcome.fallback(
                            GenerationRoute.CREATE, "create_generation_failed");
                });
        GenerationPipeline heavy = pipeline(
                GenerationRoute.HEAVY_GENERATION,
                GenerationMode.HEAVY_EXPERT,
                candidate -> {
                    GenerationExecutionContext context = candidate.requireExecution().executionContext();
                    context.consume(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
                    context.consume(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
                    context.consume(GenerationBudgetKind.REPAIR_ROUND);
                    context.consume(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
                    return GenerationPipelineOutcome.running(GenerationRoute.HEAVY_GENERATION);
                });

        executor(List.of(create, heavy)).execute(request);

        GenerationExecutionContext context = request.requireExecution().executionContext();
        assertTrue(request.requireExecution().session().isActive());
        assertEquals(4, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(1, context.used(GenerationBudgetKind.REPAIR_ROUND));
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
        ArgumentCaptor<GenerationFinalizationCommand> commandCaptor =
                ArgumentCaptor.forClass(GenerationFinalizationCommand.class);
        verify(taskFinalizer).finalizeManaged(commandCaptor.capture());
        GenerationFinalizationCommand command = commandCaptor.getValue();
        assertEquals("task-failed", command.taskId());
        assertEquals(GenerationTaskStatus.FAILED, command.status());
        assertEquals("generation_pipeline_failed", command.reason());
        assertTrue(command.memorySummary().contains("任务状态：失败"));
        assertTrue(command.memorySummary().contains("生成任务执行失败"));
        // 失败路径必须沉淀可归因的失败分类，供 L3 复盘与后续蒸馏使用。
        assertNotNull(command.outcomeQuality().failureCategory());
        verify(outcomeMemoryService).remember(org.mockito.ArgumentMatchers.argThat(memory ->
                memory.status() == GenerationTaskStatus.FAILED
                        && memory.memorySummary().equals(command.memorySummary())));
    }

    @Test
    void failureAfterAnotherOwnerClaimedCompletionMustNotPublishTerminalStateAgain() {
        GenerationPipelineRequest request = request(
                "task-completion-owned", GenerationMode.HEAVY_EXPERT, FallbackPolicy.NONE);
        assertTrue(request.execution().session().tryBeginCompletion());
        GenerationPipeline pipeline = pipeline(
                GenerationRoute.HEAVY_GENERATION,
                GenerationMode.HEAVY_EXPERT,
                ignored -> { throw new IllegalStateException("startup already terminalized"); });

        executor(List.of(pipeline)).execute(request);

        verify(runtimeLifecycleService).activate(request.execution().executionFence());
        verify(taskFinalizer, never()).finalizeManaged(any());
        verifyNoInteractions(eventPublisher, taskFinalizer, outcomeMemoryService);
        assertTrue(contextService.getByTaskId("task-completion-owned").isPresent());
    }

    @Test
    void finalizationMustUseExecutionFenceInsteadOfUnconditionalTaskCleanup() {
        GenerationPipelineRequest request = request("task-fenced-cleanup", GenerationMode.LIGHT_EDIT,
                FallbackPolicy.NONE);
        GenerationPipeline pipeline = pipeline(
                GenerationRoute.LIGHTWEIGHT_EDIT,
                GenerationMode.LIGHT_EDIT,
                ignored -> GenerationPipelineOutcome.completed(
                        GenerationRoute.LIGHTWEIGHT_EDIT,
                        GenerationTaskStatus.SUCCESS,
                        null,
                        "任务状态：成功",
                        successfulCompletionEvidence()));
        GenerationExecutionContextService cleanupService = mock(GenerationExecutionContextService.class);
        GenerationPipelineExecutor executor = new GenerationPipelineExecutor(
                List.of(pipeline), eventPublisher, sessionRegistry, cleanupService,
                runtimeLifecycleService, performanceMonitorService, workspaceReleaseService,
                taskFinalizer, outcomeMemoryService, completionPolicy,
                intentClarificationStage);

        executor.execute(request);

        verify(cleanupService).finishIfOwned(
                request.execution().taskId(),
                request.execution().executionFence(),
                GenerationTaskStatus.SUCCESS.getValue());
        verify(cleanupService, never()).finish(
                request.execution().taskId(), GenerationTaskStatus.SUCCESS.getValue());
    }

    @Test
    void successWithoutCompletionEvidenceMustFailClosedBeforeWorkspaceRelease() {
        GenerationPipelineRequest request = request(
                "task-empty-success", GenerationMode.LIGHT_EDIT, FallbackPolicy.NONE);
        GenerationPipeline pipeline = pipeline(
                GenerationRoute.LIGHTWEIGHT_EDIT,
                GenerationMode.LIGHT_EDIT,
                ignored -> GenerationPipelineOutcome.completed(
                        GenerationRoute.LIGHTWEIGHT_EDIT,
                        GenerationTaskStatus.SUCCESS,
                        null,
                        "任务状态：成功"
                )
        );

        executor(List.of(pipeline)).execute(request);

        assertEquals("failed", request.execution().executionContext().snapshot().terminalStatus());
        verify(workspaceReleaseService, never()).releaseVerified(
                request.execution().session(), CodeGenTypeEnum.VUE_PROJECT);
        verify(taskFinalizer, never()).finalizeManaged(
                org.mockito.ArgumentMatchers.argThat(command ->
                        command.status() == GenerationTaskStatus.SUCCESS));
    }

    private GenerationCompletionEvidenceSet successfulCompletionEvidence() {
        return GenerationCompletionEvidenceSet.successfulMutation(
                ExpectedValidationLevel.BUILD, "pipeline_executor_test", 1);
    }

    private GenerationPipelineExecutor executor(List<GenerationPipeline> pipelines) {
        return new GenerationPipelineExecutor(
                pipelines, eventPublisher, sessionRegistry, contextService, runtimeLifecycleService,
                performanceMonitorService, workspaceReleaseService, taskFinalizer,
                outcomeMemoryService, completionPolicy, intentClarificationStage);
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
        return request(taskId, mode, fallbackPolicy, true);
    }

    private GenerationPipelineRequest request(String taskId,
                                              GenerationMode mode,
                                              FallbackPolicy fallbackPolicy,
                                              boolean workspaceExists) {
        return request(taskId, mode, fallbackPolicy, workspaceExists, null);
    }

    private GenerationPipelineRequest request(String taskId,
                                              GenerationMode mode,
                                              FallbackPolicy fallbackPolicy,
                                              boolean workspaceExists,
                                              GenerationExecutionLimits limits) {
        App app = new App();
        app.setId(1L);
        app.setTenantId(3L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        User user = new User();
        user.setId(2L);
        GenerationTaskRequest taskRequest = new GenerationTaskRequest(app, "update title", user);
        Path root = Path.of("target/pipeline-executor-test").toAbsolutePath().normalize();
        GenerationWorkspace workspace = new GenerationWorkspace(
                1L, CodeGenTypeEnum.VUE_PROJECT, root, root, workspaceExists,
                root, root, Set.of(), Set.of());
        GenerationModeDecision decision = GenerationModeDecision.of(
                mode, 0.8, "test", fallbackPolicy, ExpectedValidationLevel.BUILD);
        GenerationExecutionContext context = limits == null
                ? contextService.start(taskId, 1L, 2L)
                : new GenerationExecutionContext(
                        taskId, 1L, 2L, Instant.now(), limits, Clock.systemUTC());
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
