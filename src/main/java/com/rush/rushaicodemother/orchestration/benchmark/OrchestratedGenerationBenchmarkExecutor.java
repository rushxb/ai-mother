package com.rush.rushaicodemother.orchestration.benchmark;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.model.vo.GenerationPerformanceSpanVO;
import com.rush.rushaicodemother.model.vo.GenerationPerformanceTaskVO;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanQueryService;
import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.GenerationTaskOrchestrator;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEvent;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationPreviewLevel;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 编排式生成基准测试执行器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrchestratedGenerationBenchmarkExecutor implements GenerationBenchmarkExecutor {

    private static final int MAX_CAPTURED_RESPONSE_CHARS = 100_000;

    private final GenerationBenchmarkFixtureService fixtureService;
    private final GenerationTaskOrchestrator orchestrator;
    private final GenerationEventPublisher eventPublisher;
    private final GenerationPerformanceMonitorService performanceMonitorService;
    private final GenerationSpanQueryService spanQueryService;
    private final GenerationBenchmarkUsageRepository usageRepository;
    private final GenerationBenchmarkValidationEngine validationEngine;
    private final GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private final GenerationWorkspaceService workspaceService;

    @Value("${app.generation-benchmark.task-timeout:PT12M}")
    private Duration taskTimeout;

    @Value("${app.generation-benchmark.cancellation-grace-timeout:PT30S}")
    private Duration cancellationGraceTimeout;

    @Value("${app.generation-benchmark.terminal-poll-interval:PT0.1S}")
    private Duration terminalPollInterval;

    @Value("${app.generation-benchmark.first-preview-observation-timeout:PT2S}")
    private Duration firstPreviewObservationTimeout;

    /**
 * 执行{@code Orchestrated}生成基准测试处理流程。
 *
 * @param task 任务
 * @return {@code Orchestrated}生成基准测试
 */
    @Override
    public GenerationBenchmarkRunResult execute(GenerationBenchmarkTask task) {
        return execute(task, GenerationPlanningVariant.CURRENT_DAG);
    }

    public GenerationBenchmarkRunResult execute(GenerationBenchmarkTask task,
                                                GenerationPlanningVariant planningVariant) {
        GenerationPlanningVariant variant = Objects.requireNonNullElse(
                planningVariant, GenerationPlanningVariant.CURRENT_DAG);
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (task == null) {
            return new GenerationBenchmarkRunResult(
                    "", "", false, false, 0, 0, 0, false, 0, "task_missing");
        }
        Instant startedAt = Instant.now();
        GenerationBenchmarkFixture fixture = null;
        GenerationTaskRequest request;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            fixture = fixtureService.create(task);
            request = withPlanningVariant(fixture.request(), variant);
        } catch (RuntimeException fixtureFailure) {
            log.warn("Benchmark fixture creation failed, benchmarkTaskId: {}, error: {}",
                    task.id(), LogExceptionSanitizer.sanitizeMessage(fixtureFailure));
            return new GenerationBenchmarkRunResult(
                    task.id(), task.mode(), false, false,
                    Duration.between(startedAt, Instant.now()).toMillis(),
                    0, 0, false, 0, safeFailureReason(fixtureFailure), 0L, 0L, 0L,
                    null, GenerationBenchmarkQualityEvidence.empty(), task.expectedRoute(), false,
                    variant, null);
        }

        AtomicBoolean buildPassed = new AtomicBoolean(false);
        AtomicBoolean buildObserved = new AtomicBoolean(false);
        AtomicBoolean fallback = new AtomicBoolean(false);
        AtomicReference<String> failureReason = new AtomicReference<>("");
        AtomicReference<String> responseText = new AtomicReference<>("");
        FirstPreviewObservation firstPreviewObservation = new FirstPreviewObservation();

        Disposable eventSubscription = null;
        Disposable streamSubscription = null;
        String taskId = "";
        boolean taskStarted = false;
        boolean terminalObserved = false;
        try {
            eventSubscription = eventPublisher.stream(request.app().getId())
                    .subscribe(event -> handleEvent(
                            event,
                            buildObserved,
                            buildPassed,
                            fallback,
                            failureReason
                    ));
            GenerationTaskResult taskResult = orchestrator.start(request);
            taskId = taskResult == null ? "" : StrUtil.blankToDefault(taskResult.taskId(), "");
            if (taskId.isBlank()) {
                throw new IllegalStateException("benchmark generation task id is missing");
            }
            taskStarted = true;
            if (taskResult.contentFlux() != null) {
                streamSubscription = taskResult.contentFlux().subscribe(
                        event -> handleStreamEvent(
                            event, buildObserved, buildPassed, failureReason,
                            firstPreviewObservation, responseText),
                        error -> {
                            log.warn("Benchmark generation stream failed, benchmarkTaskId: {}, error: {}",
                                    task.id(), LogExceptionSanitizer.sanitizeMessage(error));
                            failureReason.compareAndSet("", safeFailureReason(error));
                            firstPreviewObservation.streamTerminated();
                        },
                        firstPreviewObservation::streamTerminated
                );
            }

            DurableGenerationTaskRecord terminalTask = awaitTerminal(taskId, timeout());
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            boolean timedOut = terminalTask == null;
            if (timedOut) {
                failureReason.compareAndSet("", "benchmark_timeout:" + timeout());
                requestCancellationSafely(taskId, "benchmark_timeout");
                terminalTask = awaitTerminal(taskId, cancellationGraceTimeout());
            }
            terminalObserved = terminalTask != null;
            if (terminalTask != null && terminalTask.status() != GenerationTaskStatus.SUCCESS) {
                failureReason.compareAndSet("", terminalFailureReason(terminalTask));
            }

            boolean publicationSucceeded = terminalTask != null
                    && terminalTask.status() == GenerationTaskStatus.SUCCESS;
            Long firstPreviewLatencyMs = firstPreviewObservation.current();
            if (firstPreviewLatencyMs == null) {
                firstPreviewLatencyMs = firstPreviewFromDurableTrace(taskId);
            }
            if (publicationSucceeded && failureReason.get().isBlank() && firstPreviewLatencyMs == null) {
                firstPreviewLatencyMs = firstPreviewObservation.await(firstPreviewObservationTimeout());
            }
            GenerationPerformanceTaskVO telemetry = findTelemetry(taskId);
            if (firstPreviewLatencyMs == null) {
                firstPreviewLatencyMs = firstPreviewFromTelemetry(telemetry);
            }
            GenerationBenchmarkUsage usage = Objects.requireNonNullElse(
                    usageRepository.findByTaskId(taskId), GenerationBenchmarkUsage.empty());
            Long preparationDurationMs = preparationDuration(telemetry, taskId);
            boolean success = !timedOut && publicationSucceeded && failureReason.get().isBlank();
            boolean expectedBuildPassed = resolveBuildPassed(
                    task, success, buildObserved.get(), buildPassed.get());
            String actualMode = resolvedMode(task, telemetry);
            GenerationBenchmarkQualityEvidence qualityEvidence = terminalObserved
                    ? validationEngine.evaluate(
                            validationPlan(fixture, taskId, publicationSucceeded, actualMode),
                            responseText.get())
                    : GenerationBenchmarkQualityEvidence.empty();
            return new GenerationBenchmarkRunResult(
                    task.id(),
                    actualMode,
                    success,
                    expectedBuildPassed,
                    durationMs,
                    intValue(telemetry == null ? null : telemetry.getAiCallCount()),
                    intValue(telemetry == null ? null : telemetry.getToolCallCount()),
                    fallback.get() || hasFallback(telemetry),
                    intValue(telemetry == null ? null : telemetry.getRepairRounds()),
                    failureReason.get(),
                    usage.totalTokens(),
                    usage.creditCost(),
                    longValue(telemetry == null ? null : telemetry.getFirstTokenLatencyMs()),
                    firstPreviewLatencyMs,
                    qualityEvidence,
                    task.expectedRoute(),
                    routeAllowed(task, actualMode),
                    variant,
                    preparationDurationMs
            );
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (taskStarted && !terminalObserved) {
                requestCancellationSafely(taskId, "benchmark_execution_failed");
                try {
                    terminalObserved = awaitTerminal(taskId, cancellationGraceTimeout()) != null;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interrupted);
                } catch (RuntimeException terminalWaitFailure) {
                    failure.addSuppressed(terminalWaitFailure);
                }
            }
            log.warn("Benchmark execution failed, benchmarkTaskId: {}, error: {}",
                    task.id(), LogExceptionSanitizer.sanitizeMessage(failure));
            return new GenerationBenchmarkRunResult(
                    task.id(),
                    task.mode(),
                    false,
                    false,
                    Duration.between(startedAt, Instant.now()).toMillis(),
                    0,
                    0,
                    fallback.get(),
                    0,
                    safeFailureReason(failure),
                    0L,
                    0L,
                    0L,
                    firstPreviewObservation.current(),
                    GenerationBenchmarkQualityEvidence.empty(),
                    task.expectedRoute(),
                    false,
                    variant,
                    null
            );
        } finally {
            if (eventSubscription != null) {
                eventSubscription.dispose();
            }
            if (streamSubscription != null) {
                streamSubscription.dispose();
            }
            if (fixture != null) {
                if (!taskStarted || terminalObserved) {
                    closeFixtureSafely(fixture, task.id());
                } else {
                    log.error("Benchmark fixture cleanup deferred because the durable task is still non-terminal, "
                                    + "benchmarkTaskId: {}, generationTaskId: {}",
                            task.id(), taskId);
                }
            }
        }
    }

    private GenerationTaskRequest withPlanningVariant(GenerationTaskRequest request,
                                                      GenerationPlanningVariant variant) {
        if (request == null) {
            throw new IllegalArgumentException("Benchmark 生成请求不能为空");
        }
        GenerationResourceRequirements requirements = request.resourceRequirements();
        return new GenerationTaskRequest(
                request.app(), request.message(), request.loginUser(), requirements, variant);
    }

    /** 处理事件。 */
    private void handleEvent(GenerationEvent event,
                             AtomicBoolean buildObserved,
                             AtomicBoolean buildPassed,
                             AtomicBoolean fallback,
                             AtomicReference<String> failureReason) {
        if (event == null) {
            return;
        }
        if (event.type() == GenerationEventType.TASK_ROUTE && containsFallback(event.data())) {
            fallback.set(true);
        }
        if (event.type() == GenerationEventType.VALIDATION_RESULT) {
            buildObserved.set(true);
            boolean passed = isSuccessStatus(event.data() == null ? null : event.data().get("status"));
            buildPassed.set(passed);
            if (!passed) {
                failureReason.compareAndSet("", safeFailureReason(eventMessage(event)));
            }
        }
        if (event.type() == GenerationEventType.TASK_FAILED
                || event.type() == GenerationEventType.TASK_CANCELLED
                || event.type() == GenerationEventType.TASK_TIMED_OUT) {
            failureReason.compareAndSet("", safeFailureReason(eventMessage(event)));
        }
    }

    /** 处理流事件。 */
    private void handleStreamEvent(GenerationStreamEvent event,
                                   AtomicBoolean buildObserved,
                                   AtomicBoolean buildPassed,
                                   AtomicReference<String> failureReason,
                                   FirstPreviewObservation firstPreviewObservation,
                                   AtomicReference<String> responseText) {
        if (event == null) {
            return;
        }
        firstPreviewObservation.observe(event);
        Map<String, Object> data = event.getData();
        if (GenerationStreamEvent.AI_DELTA.equals(event.getType())) {
            appendBoundedResponse(responseText, event.getText());
        }
        if (GenerationStreamEvent.BUILD_RESULT.equals(event.getType())) {
            buildObserved.set(true);
            boolean success = boolValue(data == null ? null : data.get("success"));
            buildPassed.set(success);
            if (!success) {
                failureReason.compareAndSet("", safeFailureReason(
                        stringValue(data == null ? null : data.get("summary"), "build_failed")));
            }
        }
        if (GenerationStreamEvent.DEV_SERVER_VALIDATION.equals(event.getType())) {
            boolean passed = boolValue(data == null ? null : data.get("passed"));
            if (!passed) {
                failureReason.compareAndSet("", safeFailureReason(
                        stringValue(data == null ? null : data.get("summary"),
                                "runtime_validation_failed")));
            }
        }
        if (GenerationStreamEvent.GENERATION_ERROR.equals(event.getType())) {
            failureReason.compareAndSet("", safeFailureReason(
                    StrUtil.blankToDefault(event.getText(), "generation_error")));
        }
    }

    /** 等待{@code Terminal}完成。 */
    private DurableGenerationTaskRecord awaitTerminal(String taskId,
                                                       Duration waitTimeout) throws InterruptedException {
        if (StrUtil.isBlank(taskId)) {
            return null;
        }
        Duration boundedTimeout = positiveDuration(waitTimeout, Duration.ofMinutes(5));
        long timeoutNanos = boundedTimeout.toNanos();
        long started = System.nanoTime();
        while (true) {
            DurableGenerationTaskRecord current = runtimeLifecycleService.findByTaskId(taskId).orElse(null);
            if (current != null && current.terminal()) {
                return current;
            }
            long elapsed = Math.max(0L, System.nanoTime() - started);
            long remaining = timeoutNanos - elapsed;
            if (remaining <= 0) {
                break;
            }
            long sleepNanos = Math.min(remaining, pollInterval().toNanos());
            TimeUnit.NANOSECONDS.sleep(Math.max(1L, sleepNanos));
        }
        return runtimeLifecycleService.findByTaskId(taskId)
                .filter(DurableGenerationTaskRecord::terminal)
                .orElse(null);
    }

    private Duration timeout() {
        return positiveDuration(taskTimeout, Duration.ofMinutes(12));
    }

    private Duration cancellationGraceTimeout() {
        return positiveDuration(cancellationGraceTimeout, Duration.ofSeconds(30));
    }

    private Duration pollInterval() {
        return positiveDuration(terminalPollInterval, Duration.ofMillis(100));
    }

    private Duration firstPreviewObservationTimeout() {
        return positiveDuration(firstPreviewObservationTimeout, Duration.ofSeconds(2));
    }

    private Duration positiveDuration(Duration configured, Duration fallback) {
        return configured == null || configured.isNegative() || configured.isZero()
                ? fallback
                : configured;
    }

    /** 处理请求{@code Cancellation}安全处理。 */
    private void requestCancellationSafely(String taskId, String reason) {
        try {
            if (!runtimeLifecycleService.requestCancellation(taskId, reason)) {
                log.warn("Benchmark durable cancellation was not accepted, generationTaskId: {}", taskId);
            }
        } catch (RuntimeException cancellationFailure) {
            log.error("Benchmark durable cancellation failed, generationTaskId: {}, error: {}",
                    taskId, LogExceptionSanitizer.sanitizeMessage(cancellationFailure));
        }
    }

    private String terminalFailureReason(DurableGenerationTaskRecord task) {
        String detail = StrUtil.blankToDefault(
                task.errorMessage(),
                StrUtil.blankToDefault(
                        task.cancellationReason(),
                        task.status() == null ? "task_failed" : task.status().getValue()));
        return safeFailureReason(detail);
    }

    private GenerationBenchmarkValidationPlan validationPlan(
            GenerationBenchmarkFixture fixture,
            String taskId,
            boolean publicationSucceeded,
            String actualMode) {
        GenerationBenchmarkValidationPlan plan = fixture.validationPlan();
        if (!publicationSucceeded || "READ_ONLY".equalsIgnoreCase(actualMode)) {
            return plan;
        }
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(
                fixture.request().app().getCodeGenType());
        if (codeGenType == null) {
            throw new IllegalStateException("benchmark publication code generation type is invalid");
        }
        GenerationWorkspace publishedWorkspace = workspaceService.resolvePublished(
                fixture.request().app().getId(), codeGenType, taskId);
        return plan.withWorkspace(publishedWorkspace);
    }

    private void appendBoundedResponse(AtomicReference<String> responseText, String chunk) {
        if (responseText == null || chunk == null || chunk.isEmpty()) {
            return;
        }
        responseText.updateAndGet(current -> {
            String existing = current == null ? "" : current;
            int remaining = MAX_CAPTURED_RESPONSE_CHARS - existing.length();
            if (remaining <= 0) {
                return existing;
            }
            return existing + chunk.substring(0, Math.min(remaining, chunk.length()));
        });
    }

    /** 关闭{@code Fixture}安全处理并释放资源。 */
    private void closeFixtureSafely(GenerationBenchmarkFixture fixture, String benchmarkTaskId) {
        try {
            fixture.close();
        } catch (RuntimeException cleanupFailure) {
            log.warn("Benchmark fixture cleanup failed, benchmarkTaskId: {}, error: {}",
                    benchmarkTaskId, LogExceptionSanitizer.sanitizeMessage(cleanupFailure));
        }
    }

    private GenerationPerformanceTaskVO findTelemetry(String taskId) {
        if (StrUtil.isBlank(taskId)) {
            return null;
        }
        return performanceMonitorService.getSummary(100).getRecentTasks().stream()
                .filter(task -> taskId.equals(task.getTaskId()))
                .findFirst()
                .orElse(null);
    }

    private Long preparationDuration(GenerationPerformanceTaskVO telemetry, String taskId) {
        if (telemetry != null && telemetry.getSpans() != null) {
            Long observed = telemetry.getSpans().stream()
                    .filter(Objects::nonNull)
                    .filter(span -> "heavy_prepare".equals(span.getStage()))
                    .map(GenerationPerformanceSpanVO::getDurationMs)
                    .filter(Objects::nonNull)
                    .filter(durationMs -> durationMs >= 0)
                    .findFirst()
                    .orElse(null);
            if (observed != null) {
                return observed;
            }
        }
        if (StrUtil.isBlank(taskId)) {
            return null;
        }
        try {
            return spanQueryService.findByTaskId(taskId, GenerationSpanQueryService.MAX_LIMIT).stream()
                    .filter(Objects::nonNull)
                    .filter(span -> "heavy_prepare".equals(span.stage()))
                    .mapToLong(GenerationSpanQueryService.StoredSpan::durationMs)
                    .filter(durationMs -> durationMs >= 0)
                    .findFirst()
                    .stream()
                    .boxed()
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException queryFailure) {
            log.warn("读取模型调用前准备耗时失败，taskId: {}, error: {}",
                    taskId, LogExceptionSanitizer.sanitizeMessage(queryFailure));
            return null;
        }
    }

    /**
     * 返回首次预览{@code From}遥测。
     *
     * <p>暂定与已验证预览都算命中，口径与流事件观测一致：本字段度量「用户多久看到东西」，
     * 而暂定预览正是首预览截止线的原意。若只认 {@code time_to_first_preview}，
     * 仅发过暂定预览的任务会被判为从未预览，在观测率门禁（要求 1.0）上误判为违规。</p>
     */
    private Long firstPreviewFromTelemetry(GenerationPerformanceTaskVO telemetry) {
        if (telemetry == null || telemetry.getSpans() == null) {
            return null;
        }
        return telemetry.getSpans().stream()
                .filter(Objects::nonNull)
                .filter(span -> GenerationPreviewLevel.isPreviewSpanStage(span.getStage()))
                .map(GenerationPerformanceSpanVO::getDurationMs)
                .filter(Objects::nonNull)
                .filter(durationMs -> durationMs >= 0)
                .min(Long::compare)
                .orElse(null);
    }

    /**
     * 返回首次预览{@code From}持久追踪。
     *
     * <p>与 {@link #firstPreviewFromTelemetry} 同口径：取两级预览中最早的一条，
     * 保证跨 worker 恢复出的数值与实时观测到的是同一个里程碑。</p>
     */
    private Long firstPreviewFromDurableTrace(String taskId) {
        if (StrUtil.isBlank(taskId)) {
            return null;
        }
        try {
            return spanQueryService.findByTaskId(taskId, GenerationSpanQueryService.MAX_LIMIT).stream()
                    .filter(Objects::nonNull)
                    .filter(span -> GenerationPreviewLevel.isPreviewSpanStage(span.stage()))
                    .mapToLong(GenerationSpanQueryService.StoredSpan::durationMs)
                    .filter(durationMs -> durationMs >= 0)
                    .min()
                    .stream()
                    .boxed()
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException queryFailure) {
            log.warn("读取首预览持久化轨迹失败，继续使用其余观测来源，taskId: {}, error: {}",
                    taskId, LogExceptionSanitizer.sanitizeMessage(queryFailure));
            return null;
        }
    }

    private boolean resolveBuildPassed(GenerationBenchmarkTask task,
                                       boolean success,
                                       boolean buildObserved,
                                       boolean buildPassed) {
        if (task == null || !"build".equalsIgnoreCase(
                StrUtil.blankToDefault(task.expectedValidation(), ""))) {
            return success;
        }
        return buildObserved && buildPassed;
    }

    private String resolvedMode(GenerationBenchmarkTask task, GenerationPerformanceTaskVO telemetry) {
        if (telemetry != null && StrUtil.isNotBlank(telemetry.getMode())
                && !"unknown".equals(telemetry.getMode())) {
            return telemetry.getMode().toUpperCase();
        }
        return task == null ? "" : task.mode();
    }

    private boolean routeAllowed(GenerationBenchmarkTask task, String actualRoute) {
        if (task == null || StrUtil.isBlank(actualRoute)) {
            return false;
        }
        String normalized = actualRoute.trim().toUpperCase();
        return normalized.equals(task.expectedRoute()) && !task.forbiddenRoutes().contains(normalized);
    }

    private boolean hasFallback(GenerationPerformanceTaskVO telemetry) {
        return telemetry != null && StrUtil.isNotBlank(telemetry.getFallbackReason());
    }

    private boolean containsFallback(Map<String, Object> data) {
        return data != null && StrUtil.isNotBlank(stringValue(data.get("fallbackReason"), ""));
    }

    private String eventMessage(GenerationEvent event) {
        String reason = stringValue(event.data() == null ? null : event.data().get("reason"), "");
        return StrUtil.isNotBlank(reason)
                ? reason
                : StrUtil.blankToDefault(event.message(), "task_failed");
    }

    private String safeFailureReason(Throwable throwable) {
        return GenerationErrorClassifier.classify(throwable).message();
    }

    private String safeFailureReason(String failureDetail) {
        return GenerationErrorClassifier.classify(failureDetail).message();
    }

    private int intValue(Number value) {
        return value == null ? 0 : value.intValue();
    }

    private long longValue(Number value) {
        return value == null ? 0 : value.longValue();
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean isSuccessStatus(Object value) {
        String status = value == null ? "" : String.valueOf(value);
        return "success".equalsIgnoreCase(status)
                || "passed".equalsIgnoreCase(status)
                || "ok".equalsIgnoreCase(status);
    }

    private String stringValue(Object value, String defaultValue) {
        return value == null
                ? defaultValue
                : StrUtil.blankToDefault(String.valueOf(value), defaultValue);
    }

    private static final class FirstPreviewObservation {

        private final AtomicReference<Long> latencyMs = new AtomicReference<>();
        private final CountDownLatch resolved = new CountDownLatch(1);

        /** 观测并记录{@code First}预览观测。 */
        private void observe(GenerationStreamEvent event) {
            if (!GenerationStreamEvent.FIRST_PREVIEW_READY.equals(event.getType())
                    || event.getData() == null) {
                return;
            }
            Object elapsed = event.getData().get("elapsedMs");
            if (!(elapsed instanceof Number number)) {
                return;
            }
            long observedLatencyMs = number.longValue();
            if (observedLatencyMs >= 0 && latencyMs.compareAndSet(null, observedLatencyMs)) {
                resolved.countDown();
            }
        }

        private void streamTerminated() {
            resolved.countDown();
        }

        private Long await(Duration timeout) throws InterruptedException {
            resolved.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return latencyMs.get();
        }

        private Long current() {
            return latencyMs.get();
        }
    }
}
