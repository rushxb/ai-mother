package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanQueryService;
import com.rush.rushaicodemother.orchestration.GenerationTaskOrchestrator;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationPreviewLevel;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSubmissionReceipt;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrchestratedGenerationBenchmarkExecutorTest {

    private final GenerationBenchmarkFixtureService fixtureService = mock(GenerationBenchmarkFixtureService.class);
    private final GenerationBenchmarkRequestFactory requestFactory = new GenerationBenchmarkRequestFactory();
    private final GenerationEventPublisher eventPublisher = new GenerationEventPublisher();
    private final GenerationPerformanceMonitorService performanceMonitorService = new GenerationPerformanceMonitorService();
    private final GenerationSpanQueryService spanQueryService = mock(GenerationSpanQueryService.class);
    private final GenerationBenchmarkUsageRepository usageRepository = mock(GenerationBenchmarkUsageRepository.class);
    private final GenerationBenchmarkValidationEngine validationEngine = mock(GenerationBenchmarkValidationEngine.class);
    private final GenerationTaskRuntimeLifecycleService runtimeLifecycleService =
            mock(GenerationTaskRuntimeLifecycleService.class);
    private final GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
    private final GenerationWorkspace publishedWorkspace = workspace(
            Path.of(".").toAbsolutePath().normalize().resolve("published"));

    @Test
    void planningVariantMustReachDurableGenerationRequestAndRunResult() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenReturn(generationResult(
                "bench-planning", "heavy_generation", Flux.empty()));
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);

        GenerationBenchmarkRunResult result = executor.execute(
                new GenerationBenchmarkTask(
                        "planning", "HEAVY_EXPERT", "vue_project", "generate", "build"),
                GenerationPlanningVariant.COMPACT_PLAN
        );

        ArgumentCaptor<GenerationTaskRequest> requestCaptor =
                ArgumentCaptor.forClass(GenerationTaskRequest.class);
        verify(orchestrator).start(requestCaptor.capture());
        assertEquals(GenerationPlanningVariant.COMPACT_PLAN,
                requestCaptor.getValue().planningVariant());
        assertEquals(GenerationPlanningVariant.COMPACT_PLAN, result.planningVariant());
    }

    @Test
    void shouldExecuteOrchestratorAndCollectSuccessfulBuildMetrics() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenAnswer(invocation -> {
            GenerationTaskRequest request = invocation.getArgument(0);
            String taskId = "bench-task-1";
            performanceMonitorService.startTask(
                    taskId,
                    request.app().getId(),
                    request.loginUser().getId(),
                    "create",
                    request.app().getCodeGenType(),
                    Instant.now(),
                    decision(GenerationMode.CREATE)
            );
            performanceMonitorService.recordCreateTelemetry(taskId, Map.of("aiCallCount", 2));
            performanceMonitorService.finishTask(taskId, "success");
            return generationResult(taskId, "create", Flux.just(
                            firstPreview(450),
                            GenerationStreamEvent.buildResult("build ok", Map.of("success", true))));
        });
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);

        GenerationBenchmarkRunResult result = executor.execute(new GenerationBenchmarkTask(
                "create_bench", "CREATE", "vue_project", "生成后台", "build"
        ));

        assertTrue(result.success());
        assertTrue(result.buildPassed());
        assertEquals(2, result.aiCallCount());
        assertEquals("CREATE", result.mode());
        assertEquals(450L, result.firstPreviewLatencyMs());
    }

    @Test
    void shouldNotCountBuildTaskAsBuildPassedWithoutBuildEvidence() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenReturn(generationResult(
                "bench-task-2",
                "create",
                Flux.just(firstPreview(500), GenerationStreamEvent.agentEvent("done", Map.of()))));
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);

        GenerationBenchmarkRunResult result = executor.execute(new GenerationBenchmarkTask(
                "create_bench", "CREATE", "vue_project", "生成后台", "build"
        ));

        assertTrue(result.success());
        assertFalse(result.buildPassed());
    }

    @Test
    void shouldCaptureTaskFailedEventAsBenchmarkFailure() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenAnswer(invocation -> {
            GenerationTaskRequest request = invocation.getArgument(0);
            eventPublisher.publish(
                    request,
                    GenerationEventType.TASK_FAILED,
                    "failed",
                    Map.of("reason", "provider-api-key=secret-value")
            );
            return generationResult("bench-task-3", "agent_edit", Flux.never());
        });
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);

        GenerationBenchmarkRunResult result = executor.execute(new GenerationBenchmarkTask(
                "edit_bench", "AGENT_EDIT", "vue_project", "修复错误", "fast"
        ));

        assertFalse(result.success());
        assertEquals("代码生成失败，请稍后重试。", result.failureReason());
        assertFalse(result.failureReason().contains("secret-value"));
    }

    @Test
    void shouldUseValidationResultAsBuildEvidenceForEditBenchmarks() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenAnswer(invocation -> {
            GenerationTaskRequest request = invocation.getArgument(0);
            eventPublisher.publish(request, GenerationEventType.VALIDATION_RESULT, "validated", Map.of("status", "success"));
            eventPublisher.publish(request, GenerationEventType.TASK_DONE, "done", Map.of());
            return generationResult("bench-task-4", "agent_edit", Flux.just(firstPreview(350)));
        });
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);

        GenerationBenchmarkRunResult result = executor.execute(new GenerationBenchmarkTask(
                "edit_bench", "AGENT_EDIT", "vue_project", "修复错误", "build"
        ));

        assertTrue(result.success());
        assertTrue(result.buildPassed());
    }

    @Test
    void shouldNotExposeRawStreamFailureDetails() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenReturn(generationResult(
                "bench-task-5",
                "create",
                Flux.error(new IllegalStateException("provider-api-key=secret-value"))));
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);

        GenerationBenchmarkRunResult result = executor.execute(new GenerationBenchmarkTask(
                "create_bench", "CREATE", "vue_project", "生成后台", "build"
        ));

        assertFalse(result.success());
        assertEquals("代码生成失败，请稍后重试。", result.failureReason());
        assertFalse(result.failureReason().contains("secret-value"));
    }

    @Test
    void shouldNotExposeRawOrchestrationFailureDetails() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenThrow(
                new IllegalStateException("provider-api-key=secret-value")
        );
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);

        GenerationBenchmarkRunResult result = executor.execute(new GenerationBenchmarkTask(
                "create_bench", "CREATE", "vue_project", "生成后台", "build"
        ));

        assertFalse(result.success());
        assertEquals("代码生成失败，请稍后重试。", result.failureReason());
        assertFalse(result.failureReason().contains("secret-value"));
    }

    @Test
    void shouldAttachDeterministicQualityEvidenceToRunResult() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenReturn(generationResult("bench-task-quality", "agent_edit", Flux.just(
                        firstPreview(400),
                        GenerationStreamEvent.buildResult("build ok", Map.of("success", true)))));
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);
        GenerationBenchmarkQualityEvidence evidence = new GenerationBenchmarkQualityEvidence(java.util.List.of(
                GenerationBenchmarkRuleResult.passed(
                        "functional", GenerationBenchmarkQualityDimension.FUNCTIONAL)
        ));
        when(validationEngine.evaluate(any())).thenReturn(evidence);

        GenerationBenchmarkRunResult result = executor.execute(new GenerationBenchmarkTask(
                "edit_quality", "AGENT_EDIT", "vue_project", "edit", "build"
        ));

        assertTrue(result.qualityEvidence().passed(GenerationBenchmarkQualityDimension.FUNCTIONAL));
        ArgumentCaptor<GenerationBenchmarkValidationPlan> plan =
                ArgumentCaptor.forClass(GenerationBenchmarkValidationPlan.class);
        verify(validationEngine).evaluate(plan.capture());
        assertSame(publishedWorkspace, plan.getValue().workspace());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "LIGHT_EDIT", "AGENT_EDIT", "HEAVY_EXPERT"})
    void shouldCaptureFirstPreviewForEveryGenerationMode(String mode) {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenReturn(generationResult(
                "bench-preview-" + mode.toLowerCase(),
                mode.toLowerCase(),
                Flux.just(firstPreview(0))));

        GenerationBenchmarkRunResult result = executor(orchestrator).execute(
                new GenerationBenchmarkTask(
                        "preview-" + mode.toLowerCase(), mode, "vue_project", "generate", "fast"));

        assertTrue(result.success());
        assertEquals(0L, result.firstPreviewLatencyMs());
        assertTrue(result.firstPreviewObserved());
    }

    @Test
    void successfulTaskWithoutPreviewMustRemainExplicitlyUnobserved() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenReturn(generationResult("bench-preview-missing", "create", Flux.empty()));

        GenerationBenchmarkRunResult result = executor(orchestrator).execute(
                new GenerationBenchmarkTask(
                        "preview-missing", "CREATE", "vue_project", "generate", "fast"));

        assertTrue(result.success());
        assertNull(result.firstPreviewLatencyMs());
        assertFalse(result.firstPreviewObserved());
    }

    @Test
    void shouldDrainDelayedTaskStreamAfterDurableTerminalState() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenReturn(generationResult(
                "bench-preview-delayed",
                "create",
                Flux.just(firstPreview(321)).delaySubscription(Duration.ofMillis(20))));
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);
        ReflectionTestUtils.setField(
                executor, "firstPreviewObservationTimeout", Duration.ofMillis(200));

        GenerationBenchmarkRunResult result = executor.execute(new GenerationBenchmarkTask(
                "preview-delayed", "CREATE", "vue_project", "generate", "fast"));

        assertTrue(result.success());
        assertEquals(321L, result.firstPreviewLatencyMs());
    }

    @Test
    void shouldRecoverFirstPreviewFromDurableTraceAcrossWorkerInstances() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenReturn(generationResult("bench-preview-trace", "create", Flux.empty()));
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);
        when(spanQueryService.findByTaskId(
                "bench-preview-trace", GenerationSpanQueryService.MAX_LIMIT)).thenReturn(java.util.List.of(
                new GenerationSpanQueryService.StoredSpan(
                        "span-preview",
                        "bench-preview-trace",
                        "time_to_first_preview",
                        "pipeline",
                        "met",
                        Instant.now().minusMillis(777),
                        Instant.now(),
                        777,
                        "create-preview-first"
                )
        ));

        GenerationBenchmarkRunResult result = executor.execute(
                new GenerationBenchmarkTask(
                        "preview-trace", "CREATE", "vue_project", "generate", "fast"));

        assertTrue(result.success());
        assertEquals(777L, result.firstPreviewLatencyMs());
    }

    /**
     * 只落下暂定预览 span 的任务，跨 worker 恢复时也必须算「已观测到首预览」。
     *
     * <p>EXPERT 链路的暂定预览记 {@code time_to_provisional_preview}，已验证预览记
     * {@code time_to_first_preview}。若持久追踪只认后者，一个用户明明早就看到了页面的任务
     * 会被判为从未预览 —— 而观测率门禁要求 1.0，于是把正常任务判成发布违规。
     * 断言取两级中最早的一条，与实时流事件观测同口径。</p>
     */
    @Test
    void provisionalPreviewSpanMustCountAsObservedFirstPreview() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenReturn(
                generationResult("bench-preview-provisional", "heavy_expert", Flux.empty()));
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);
        when(spanQueryService.findByTaskId(
                "bench-preview-provisional", GenerationSpanQueryService.MAX_LIMIT)).thenReturn(java.util.List.of(
                storedSpan("span-provisional", "bench-preview-provisional",
                        GenerationPreviewLevel.PROVISIONAL.spanStage(), 1200)));

        GenerationBenchmarkRunResult result = executor.execute(
                new GenerationBenchmarkTask(
                        "preview-provisional", "HEAVY_EXPERT", "vue_project", "generate", "fast"));

        assertTrue(result.success());
        assertTrue(result.firstPreviewObserved(), "暂定预览也应算作已观测到首预览");
        assertEquals(1200L, result.firstPreviewLatencyMs());
    }

    /** 两级预览都落了 span 时，取最早的那条：度量的是用户多久看到东西。 */
    @Test
    void earliestPreviewLevelMustWinWhenBothSpansExist() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenReturn(
                generationResult("bench-preview-both", "heavy_expert", Flux.empty()));
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);
        when(spanQueryService.findByTaskId(
                "bench-preview-both", GenerationSpanQueryService.MAX_LIMIT)).thenReturn(java.util.List.of(
                storedSpan("span-verified", "bench-preview-both",
                        GenerationPreviewLevel.VERIFIED.spanStage(), 9000),
                storedSpan("span-provisional", "bench-preview-both",
                        GenerationPreviewLevel.PROVISIONAL.spanStage(), 1500)));

        GenerationBenchmarkRunResult result = executor.execute(
                new GenerationBenchmarkTask(
                        "preview-both", "HEAVY_EXPERT", "vue_project", "generate", "fast"));

        assertTrue(result.success());
        assertEquals(1500L, result.firstPreviewLatencyMs(), "应取两级预览中最早的一条");
    }

    /** 与首预览无关的 span 不得被误认成预览观测。 */
    @Test
    void unrelatedSpansMustNotBeMistakenForPreviewObservation() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenReturn(
                generationResult("bench-preview-unrelated", "create", Flux.empty()));
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);
        when(spanQueryService.findByTaskId(
                "bench-preview-unrelated", GenerationSpanQueryService.MAX_LIMIT)).thenReturn(java.util.List.of(
                storedSpan("span-build", "bench-preview-unrelated", "build_validation", 2000)));

        GenerationBenchmarkRunResult result = executor.execute(
                new GenerationBenchmarkTask(
                        "preview-unrelated", "CREATE", "vue_project", "generate", "fast"));

        assertTrue(result.success());
        assertFalse(result.firstPreviewObserved());
        assertNull(result.firstPreviewLatencyMs());
    }

    /** 构造一条持久化 span，便于按阶段名与耗时断言首预览观测口径。 */
    private GenerationSpanQueryService.StoredSpan storedSpan(String spanId,
                                                             String taskId,
                                                             String stage,
                                                             long durationMs) {
        Instant endedAt = Instant.now();
        return new GenerationSpanQueryService.StoredSpan(
                spanId, taskId, stage, "pipeline", "met",
                endedAt.minusMillis(durationMs), endedAt, durationMs, "create-preview-first");
    }

    @Test
    void timeoutMustRequestDurableCancellationAndCleanupOnlyAfterTerminalState() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenReturn(generationResult("bench-timeout", "create", Flux.never()));
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);
        ReflectionTestUtils.setField(executor, "taskTimeout", Duration.ofMillis(15));
        ReflectionTestUtils.setField(executor, "cancellationGraceTimeout", Duration.ofMillis(100));
        ReflectionTestUtils.setField(executor, "terminalPollInterval", Duration.ofMillis(1));
        AtomicBoolean cancellationRequested = new AtomicBoolean(false);
        AtomicBoolean terminalObserved = new AtomicBoolean(false);
        AtomicBoolean cleaned = new AtomicBoolean(false);
        when(runtimeLifecycleService.requestCancellation("bench-timeout", "benchmark_timeout"))
                .thenAnswer(ignored -> {
                    cancellationRequested.set(true);
                    return true;
                });
        when(runtimeLifecycleService.findByTaskId("bench-timeout")).thenAnswer(ignored -> {
            if (!cancellationRequested.get()) {
                return Optional.of(record("bench-timeout", GenerationTaskStatus.RUNNING));
            }
            terminalObserved.set(true);
            return Optional.of(record("bench-timeout", GenerationTaskStatus.CANCELLED));
        });
        doAnswer(invocation -> {
            GenerationBenchmarkTask task = invocation.getArgument(0);
            GenerationBenchmarkFixture original = fixture(task);
            return new GenerationBenchmarkFixture(
                    original.request(),
                    original.validationPlan(),
                    () -> {
                        assertTrue(terminalObserved.get());
                        cleaned.set(true);
                    });
        }).when(fixtureService).create(any());

        GenerationBenchmarkRunResult result = executor.execute(new GenerationBenchmarkTask(
                "timeout", "CREATE", "vue_project", "generate", "build"));

        assertFalse(result.success());
        assertTrue(cancellationRequested.get());
        assertTrue(cleaned.get());
        verify(runtimeLifecycleService).requestCancellation("bench-timeout", "benchmark_timeout");
    }

    @Test
    void nonTerminalTaskAfterCancellationGraceMustKeepFixtureForDeferredCleanup() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenReturn(generationResult("bench-stuck", "create", Flux.never()));
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);
        ReflectionTestUtils.setField(executor, "taskTimeout", Duration.ofMillis(10));
        ReflectionTestUtils.setField(executor, "cancellationGraceTimeout", Duration.ofMillis(10));
        ReflectionTestUtils.setField(executor, "terminalPollInterval", Duration.ofMillis(1));
        AtomicBoolean cleaned = new AtomicBoolean(false);
        when(runtimeLifecycleService.findByTaskId("bench-stuck"))
                .thenReturn(Optional.of(record("bench-stuck", GenerationTaskStatus.RUNNING)));
        when(runtimeLifecycleService.requestCancellation("bench-stuck", "benchmark_timeout"))
                .thenReturn(true);
        doAnswer(invocation -> {
            GenerationBenchmarkFixture original = fixture(invocation.getArgument(0));
            return new GenerationBenchmarkFixture(
                    original.request(), original.validationPlan(), () -> cleaned.set(true));
        }).when(fixtureService).create(any());

        GenerationBenchmarkRunResult result = executor.execute(new GenerationBenchmarkTask(
                "stuck", "CREATE", "vue_project", "generate", "build"));

        assertFalse(result.success());
        assertFalse(cleaned.get());
        verify(runtimeLifecycleService).requestCancellation("bench-stuck", "benchmark_timeout");
        verify(validationEngine, never()).evaluate(any());
    }

    private OrchestratedGenerationBenchmarkExecutor executor(GenerationTaskOrchestrator orchestrator) {
        when(fixtureService.create(any())).thenAnswer(invocation -> fixture(invocation.getArgument(0)));
        when(usageRepository.findByTaskId(any())).thenReturn(GenerationBenchmarkUsage.empty());
        when(validationEngine.evaluate(any())).thenReturn(GenerationBenchmarkQualityEvidence.empty());
        when(spanQueryService.findByTaskId(anyString(), eq(GenerationSpanQueryService.MAX_LIMIT)))
                .thenReturn(java.util.List.of());
        when(runtimeLifecycleService.findByTaskId(anyString())).thenAnswer(invocation -> Optional.of(
                record(invocation.getArgument(0), GenerationTaskStatus.SUCCESS)));
        when(runtimeLifecycleService.requestCancellation(anyString(), anyString())).thenReturn(true);
        when(workspaceService.resolvePublished(anyLong(), eq(CodeGenTypeEnum.VUE_PROJECT), anyString()))
                .thenReturn(publishedWorkspace);
        OrchestratedGenerationBenchmarkExecutor executor = new OrchestratedGenerationBenchmarkExecutor(
                fixtureService,
                orchestrator,
                eventPublisher,
                performanceMonitorService,
                spanQueryService,
                usageRepository,
                validationEngine,
                runtimeLifecycleService,
                workspaceService
        );
        ReflectionTestUtils.setField(executor, "taskTimeout", Duration.ofMillis(200));
        ReflectionTestUtils.setField(executor, "cancellationGraceTimeout", Duration.ofMillis(50));
        ReflectionTestUtils.setField(executor, "terminalPollInterval", Duration.ofMillis(1));
        ReflectionTestUtils.setField(executor, "firstPreviewObservationTimeout", Duration.ofMillis(20));
        return executor;
    }

    private GenerationTaskResult generationResult(
            String taskId,
            String route,
            Flux<GenerationStreamEvent> contentFlux
    ) {
        Instant submittedAt = Instant.parse("2026-07-28T00:00:00Z");
        GenerationTaskSubmissionReceipt submission = new GenerationTaskSubmissionReceipt(
                taskId,
                101L,
                route,
                GenerationTaskStatus.QUEUED,
                submittedAt,
                submittedAt.plusSeconds(60)
        );
        return new GenerationTaskResult(submission, null, contentFlux);
    }

    private GenerationStreamEvent firstPreview(long elapsedMs) {
        return GenerationStreamEvent.firstPreviewReady(
                "preview ready", Map.of("elapsedMs", elapsedMs));
    }

    private GenerationBenchmarkFixture fixture(GenerationBenchmarkTask task) {
        User user = new User();
        user.setId(9L);
        user.setUserAccount("generation-benchmark");
        App app = App.builder()
                .id(101L)
                .userId(user.getId())
                .appName("benchmark-" + task.id())
                .initPrompt(task.prompt())
                .codeGenType(task.codeGenType())
                .build();
        return new GenerationBenchmarkFixture(requestFactory.create(task, app, user), () -> { });
    }

    private GenerationModeDecision decision(GenerationMode mode) {
        return new GenerationModeDecision(
                mode,
                1.0,
                "test",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.FAST,
                ""
        );
    }

    private DurableGenerationTaskRecord record(String taskId, GenerationTaskStatus status) {
        return new DurableGenerationTaskRecord(
                taskId,
                101L,
                9L,
                1L,
                "create",
                status,
                status == null ? "" : status.getValue(),
                "",
                Instant.now(),
                Instant.now().plusSeconds(60),
                status == GenerationTaskStatus.CANCELLED,
                status == GenerationTaskStatus.CANCELLED ? "benchmark_timeout" : "",
                status != null && status.isTerminal() ? null : "worker-1",
                status != null && status.isTerminal() ? null : Instant.now().plusSeconds(30),
                Instant.now(),
                0,
                1L,
                status != null && status.isTerminal() ? Instant.now() : null,
                status == GenerationTaskStatus.FAILED ? "generation_failed" : ""
        );
    }

    private GenerationWorkspace workspace(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        return new GenerationWorkspace(
                101L,
                CodeGenTypeEnum.VUE_PROJECT,
                normalized,
                normalized,
                true,
                normalized,
                null,
                Set.of(),
                Set.of("vue", "ts", "json")
        );
    }
}
