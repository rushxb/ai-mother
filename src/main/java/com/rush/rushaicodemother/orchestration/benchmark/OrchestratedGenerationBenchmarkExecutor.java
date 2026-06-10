package com.rush.rushaicodemother.orchestration.benchmark;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.vo.GenerationPerformanceTaskVO;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationTaskOrchestrator;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEvent;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
public class OrchestratedGenerationBenchmarkExecutor implements GenerationBenchmarkExecutor {

    private final GenerationBenchmarkRequestFactory requestFactory;
    private final GenerationTaskOrchestrator orchestrator;
    private final GenerationEventPublisher eventPublisher;
    private final GenerationPerformanceMonitorService performanceMonitorService;

    @Value("${generation.benchmark.task-timeout:PT5M}")
    private Duration taskTimeout;

    @Override
    public GenerationBenchmarkRunResult execute(GenerationBenchmarkTask task) {
        if (task == null) {
            return new GenerationBenchmarkRunResult("", "", false, false, 0, 0, 0, false, 0, "task_missing");
        }
        Instant startedAt = Instant.now();
        GenerationTaskRequest request = requestFactory.create(task);
        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicBoolean terminalSuccess = new AtomicBoolean(false);
        AtomicBoolean buildPassed = new AtomicBoolean(false);
        AtomicBoolean buildObserved = new AtomicBoolean(false);
        AtomicBoolean fallback = new AtomicBoolean(false);
        AtomicReference<String> failureReason = new AtomicReference<>("");

        Disposable eventSubscription = eventPublisher.stream(request.app().getId())
                .subscribe(event -> handleEvent(
                        event,
                        terminalSuccess,
                        buildObserved,
                        buildPassed,
                        fallback,
                        failureReason,
                        doneLatch
                ));
        Disposable streamSubscription = null;
        String taskId = "";
        try {
            GenerationTaskResult taskResult = orchestrator.start(request);
            taskId = taskResult == null ? "" : StrUtil.blankToDefault(taskResult.taskId(), "");
            if (taskResult != null && taskResult.contentFlux() != null) {
                streamSubscription = taskResult.contentFlux()
                        .subscribe(
                                event -> handleStreamEvent(event, buildObserved, buildPassed, failureReason),
                                error -> {
                                    failureReason.compareAndSet("", StrUtil.blankToDefault(error.getMessage(), error.getClass().getSimpleName()));
                                    doneLatch.countDown();
                                },
                                () -> {
                                    if (failureReason.get().isBlank()) {
                                        terminalSuccess.set(true);
                                    }
                                    doneLatch.countDown();
                                }
                        );
            }
            boolean completed = await(doneLatch, timeout());
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            if (!completed) {
                failureReason.compareAndSet("", "benchmark_timeout:" + timeout());
            }
            GenerationPerformanceTaskVO telemetry = findTelemetry(taskId);
            boolean success = completed && terminalSuccess.get() && failureReason.get().isBlank();
            boolean expectedBuildPassed = resolveBuildPassed(task, success, buildObserved.get(), buildPassed.get());
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
                    failureReason.get()
            );
        } catch (Exception e) {
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            return new GenerationBenchmarkRunResult(
                    task.id(),
                    task.mode(),
                    false,
                    false,
                    durationMs,
                    0,
                    0,
                    fallback.get(),
                    0,
                    StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName())
            );
        } finally {
            eventSubscription.dispose();
            if (streamSubscription != null) {
                streamSubscription.dispose();
            }
        }
    }

    private void handleEvent(GenerationEvent event,
                             AtomicBoolean terminalSuccess,
                             AtomicBoolean buildObserved,
                             AtomicBoolean buildPassed,
                             AtomicBoolean fallback,
                             AtomicReference<String> failureReason,
                             CountDownLatch doneLatch) {
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
                failureReason.compareAndSet("", eventMessage(event));
            }
        }
        if (event.type() == GenerationEventType.TASK_DONE) {
            terminalSuccess.set(true);
            doneLatch.countDown();
        } else if (event.type() == GenerationEventType.TASK_FAILED) {
            failureReason.compareAndSet("", eventMessage(event));
            doneLatch.countDown();
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
                failureReason.compareAndSet("", stringValue(data == null ? null : data.get("summary"), "build_failed"));
            }
        }
        if (GenerationStreamEvent.DEV_SERVER_VALIDATION.equals(event.getType())) {
            boolean passed = boolValue(data == null ? null : data.get("passed"));
            if (!passed) {
                failureReason.compareAndSet("", stringValue(data == null ? null : data.get("summary"), "runtime_validation_failed"));
            }
        }
        if (GenerationStreamEvent.GENERATION_ERROR.equals(event.getType())) {
            failureReason.compareAndSet("", StrUtil.blankToDefault(event.getText(), "generation_error"));
        }
    }

    private boolean await(CountDownLatch doneLatch, Duration timeout) throws InterruptedException {
        return doneLatch.await(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
    }

    private Duration timeout() {
        return taskTimeout == null || taskTimeout.isNegative() || taskTimeout.isZero()
                ? Duration.ofMinutes(5)
                : taskTimeout;
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
        if (task == null || !"build".equalsIgnoreCase(StrUtil.blankToDefault(task.expectedValidation(), ""))) {
            return success;
        }
        return buildObserved && buildPassed;
    }

    private String resolvedMode(GenerationBenchmarkTask task, GenerationPerformanceTaskVO telemetry) {
        if (telemetry != null && StrUtil.isNotBlank(telemetry.getMode()) && !"unknown".equals(telemetry.getMode())) {
            return telemetry.getMode().toUpperCase();
        }
        return task == null ? "" : task.mode();
    }

    private boolean hasFallback(GenerationPerformanceTaskVO telemetry) {
        return telemetry != null && StrUtil.isNotBlank(telemetry.getFallbackReason());
    }

    private boolean containsFallback(Map<String, Object> data) {
        if (data == null) {
            return false;
        }
        return StrUtil.isNotBlank(stringValue(data.get("fallbackReason"), ""));
    }

    private String eventMessage(GenerationEvent event) {
        String reason = stringValue(event.data() == null ? null : event.data().get("reason"), "");
        return StrUtil.isNotBlank(reason) ? reason : StrUtil.blankToDefault(event.message(), "task_failed");
    }

    private int intValue(Number value) {
        return value == null ? 0 : value.intValue();
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
        return value == null ? defaultValue : StrUtil.blankToDefault(String.valueOf(value), defaultValue);
    }
}
