package com.rush.rushaicodemother.orchestration.benchmark;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.model.vo.GenerationPerformanceTaskVO;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationTaskOrchestrator;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEvent;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrchestratedGenerationBenchmarkExecutor implements GenerationBenchmarkExecutor {

    private final GenerationBenchmarkFixtureService fixtureService;
    private final GenerationTaskOrchestrator orchestrator;
    private final GenerationEventPublisher eventPublisher;
    private final GenerationPerformanceMonitorService performanceMonitorService;
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

    @Override
    public GenerationBenchmarkRunResult execute(GenerationBenchmarkTask task) {
        if (task == null) {
            return new GenerationBenchmarkRunResult(
                    "", "", false, false, 0, 0, 0, false, 0, "task_missing");
        }
        Instant startedAt = Instant.now();
        GenerationBenchmarkFixture fixture = null;
        GenerationTaskRequest request;
        try {
            fixture = fixtureService.create(task);
            request = fixture.request();
        } catch (RuntimeException fixtureFailure) {
            log.warn("Benchmark fixture creation failed, benchmarkTaskId: {}, error: {}",
                    task.id(), LogExceptionSanitizer.sanitizeMessage(fixtureFailure));
            return new GenerationBenchmarkRunResult(
                    task.id(), task.mode(), false, false,
                    Duration.between(startedAt, Instant.now()).toMillis(),
                    0, 0, false, 0, safeFailureReason(fixtureFailure));
        }

        AtomicBoolean buildPassed = new AtomicBoolean(false);
        AtomicBoolean buildObserved = new AtomicBoolean(false);
        AtomicBoolean fallback = new AtomicBoolean(false);
        AtomicReference<String> failureReason = new AtomicReference<>("");

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
                        event -> handleStreamEvent(event, buildObserved, buildPassed, failureReason),
                        error -> {
                            log.warn("Benchmark generation stream failed, benchmarkTaskId: {}, error: {}",
                                    task.id(), LogExceptionSanitizer.sanitizeMessage(error));
                            failureReason.compareAndSet("", safeFailureReason(error));
                        },
                        () -> { }
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

            GenerationPerformanceTaskVO telemetry = findTelemetry(taskId);
            GenerationBenchmarkUsage usage = Objects.requireNonNullElse(
                    usageRepository.findByTaskId(taskId), GenerationBenchmarkUsage.empty());
            boolean publicationSucceeded = terminalTask != null
                    && terminalTask.status() == GenerationTaskStatus.SUCCESS;
            boolean success = !timedOut && publicationSucceeded && failureReason.get().isBlank();
            boolean expectedBuildPassed = resolveBuildPassed(
                    task, success, buildObserved.get(), buildPassed.get());
            GenerationBenchmarkQualityEvidence qualityEvidence = terminalObserved
                    ? validationEngine.evaluate(validationPlan(
                            fixture, taskId, publicationSucceeded))
                    : GenerationBenchmarkQualityEvidence.empty();
            return new GenerationBenchmarkRunResult(
                    task.id(),
                    resolvedMode(task, telemetry),
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
                    qualityEvidence
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
                    safeFailureReason(failure)
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

    private void handleStreamEvent(GenerationStreamEvent event,
                                   AtomicBoolean buildObserved,
                                   AtomicBoolean buildPassed,
                                   AtomicReference<String> failureReason) {
        if (event == null) {
            return;
        }
        Map<String, Object> data = event.getData();
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

    private Duration positiveDuration(Duration configured, Duration fallback) {
        return configured == null || configured.isNegative() || configured.isZero()
                ? fallback
                : configured;
    }

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
            boolean publicationSucceeded) {
        GenerationBenchmarkValidationPlan plan = fixture.validationPlan();
        if (!publicationSucceeded) {
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
}
