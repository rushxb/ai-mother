package com.rush.rushaicodemother.orchestration.runtime.model;

import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** 统一执行根模型尝试、受限重试和对应的生产遥测。 */
@Component
public class RootModelRetryExecutor {

    private final GenerationPerformanceMonitorService performanceMonitorService;
    private final AiModelMetricsCollector metricsCollector;
    private final RootModelRetryPolicy retryPolicy;

    @Autowired
    public RootModelRetryExecutor(GenerationPerformanceMonitorService performanceMonitorService,
                                  AiModelMetricsCollector metricsCollector,
                                  AiModelRuntimeProperties runtimeProperties) {
        this(performanceMonitorService, metricsCollector, new RootModelRetryPolicy(runtimeProperties));
    }

    public RootModelRetryExecutor(GenerationPerformanceMonitorService performanceMonitorService,
                                  AiModelMetricsCollector metricsCollector,
                                  RootModelRetryPolicy retryPolicy) {
        this.performanceMonitorService = Objects.requireNonNull(
                performanceMonitorService, "生成性能监控服务不能为空");
        this.metricsCollector = metricsCollector;
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "根模型重试策略不能为空");
    }

    public <T> Flux<T> execute(Supplier<Flux<T>> attemptSupplier,
                               int maxRetries,
                               GenerationExecutionContext executionContext,
                               Predicate<Throwable> retriableError) {
        Objects.requireNonNull(attemptSupplier, "根模型尝试供应器不能为空");
        Objects.requireNonNull(retriableError, "根模型可重试错误判断器不能为空");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("根模型最大重试次数不能小于 0");
        }

        Flux<T> attempt = Flux.defer(() -> {
            Instant startedAt = Instant.now();
            Flux<T> stream = Flux.defer(() -> Objects.requireNonNull(
                    attemptSupplier.get(), "根模型尝试流不能为空"));
            return observeAttempt(stream, executionContext, startedAt);
        });
        if (maxRetries == 0) {
            return attempt;
        }
        return retry(attempt, maxRetries, executionContext, retriableError);
    }

    private <T> Flux<T> retry(Flux<T> attempt,
                              int maxRetries,
                              GenerationExecutionContext executionContext,
                              Predicate<Throwable> retriableError) {
        return Flux.defer(() -> {
            AtomicInteger scheduledRetries = new AtomicInteger();
            AtomicLong totalWaitNanos = new AtomicLong();
            AtomicBoolean terminalOutcomeRecorded = new AtomicBoolean(false);
            AtomicReference<Throwable> lastFailure = new AtomicReference<>();
            Retry reactorRetry = Retry.from(retrySignals -> retrySignals.concatMap(retrySignal -> {
                Throwable failure = retrySignal.failure();
                lastFailure.set(failure);
                if (!retriableError.test(failure)) {
                    recordRetry("skipped_non_retriable", failure, Duration.ZERO);
                    return Mono.<Long>error(failure);
                }
                if (retrySignal.totalRetries() >= maxRetries) {
                    recordRetry("exhausted", failure, Duration.ZERO);
                    return Mono.<Long>error(failure);
                }

                RootModelRetryPolicy.Decision decision = retryPolicy.decide(
                        retrySignal.totalRetries(), executionContext);
                if (!decision.retryAllowed()) {
                    String outcome = decision.rejection()
                            == RootModelRetryPolicy.Rejection.BUDGET_EXHAUSTED
                            ? "skipped_budget"
                            : "skipped_deadline";
                    recordRetry(outcome, failure, Duration.ZERO);
                    return Mono.<Long>error(failure);
                }

                scheduledRetries.incrementAndGet();
                recordRetry("scheduled", failure, decision.delay());
                Instant waitStartedAt = Instant.now();
                AtomicBoolean waitRecorded = new AtomicBoolean(false);
                return Mono.delay(decision.delay())
                        .doOnSuccess(ignored -> recordRetryWait(
                                executionContext,
                                "success",
                                failure,
                                waitStartedAt,
                                totalWaitNanos,
                                waitRecorded
                        ))
                        .doFinally(signalType -> {
                            if (signalType == SignalType.CANCEL) {
                                recordRetryWait(
                                        executionContext,
                                        "cancelled",
                                        failure,
                                        waitStartedAt,
                                        totalWaitNanos,
                                        waitRecorded
                                );
                            }
                        });
            }));

            return attempt.retryWhen(reactorRetry)
                    .doOnComplete(() -> recordTerminalRetryOutcome(
                            "recovered", lastFailure.get(), scheduledRetries, totalWaitNanos,
                            terminalOutcomeRecorded))
                    .doOnError(failure -> recordTerminalRetryOutcome(
                            "failed", failure, scheduledRetries, totalWaitNanos,
                            terminalOutcomeRecorded))
                    .doFinally(signalType -> {
                        if (signalType == SignalType.CANCEL) {
                            recordTerminalRetryOutcome(
                                    "cancelled", lastFailure.get(), scheduledRetries,
                                    totalWaitNanos, terminalOutcomeRecorded);
                        }
                    });
        });
    }

    private <T> Flux<T> observeAttempt(Flux<T> stream,
                                       GenerationExecutionContext executionContext,
                                       Instant startedAt) {
        AtomicBoolean recorded = new AtomicBoolean(false);
        return stream
                .doOnComplete(() -> recordAttemptOnce(
                        recorded, "success", null, startedAt, executionContext))
                .doOnError(failure -> recordAttemptOnce(
                        recorded, "failed", failure, startedAt, executionContext))
                .doOnCancel(() -> recordAttemptOnce(
                        recorded, "cancelled", null, startedAt, executionContext));
    }

    private void recordAttemptOnce(AtomicBoolean recorded,
                                   String outcome,
                                   Throwable failure,
                                   Instant startedAt,
                                   GenerationExecutionContext executionContext) {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        Duration duration = elapsedSince(startedAt);
        if (metricsCollector != null) {
            metricsCollector.recordRootModelAttempt(outcome, failure, duration);
        }
        if (executionContext != null) {
            performanceMonitorService.recordSpan(
                    executionContext.taskId(),
                    "root_model_attempt",
                    GenerationSpanCategory.MODEL,
                    outcome,
                    duration,
                    errorCategory(failure)
            );
        }
    }

    private void recordRetry(String outcome, Throwable failure, Duration delay) {
        if (metricsCollector != null) {
            metricsCollector.recordRootModelRetry(outcome, failure, delay);
        }
    }

    private void recordRetryWait(GenerationExecutionContext executionContext,
                                 String outcome,
                                 Throwable failure,
                                 Instant startedAt,
                                 AtomicLong totalWaitNanos,
                                 AtomicBoolean recorded) {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        Duration duration = elapsedSince(startedAt);
        totalWaitNanos.updateAndGet(current -> saturatedAdd(current, duration.toNanos()));
        if (executionContext != null) {
            performanceMonitorService.recordSpan(
                    executionContext.taskId(),
                    "root_model_retry_backoff",
                    GenerationSpanCategory.MODEL,
                    outcome,
                    duration,
                    errorCategory(failure)
            );
        }
    }

    private void recordTerminalRetryOutcome(String outcome,
                                            Throwable failure,
                                            AtomicInteger scheduledRetries,
                                            AtomicLong totalWaitNanos,
                                            AtomicBoolean recorded) {
        if (scheduledRetries.get() == 0 || !recorded.compareAndSet(false, true)) {
            return;
        }
        recordRetry(outcome, failure, Duration.ofNanos(Math.max(0L, totalWaitNanos.get())));
    }

    private long saturatedAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    private Duration elapsedSince(Instant startedAt) {
        Duration duration = Duration.between(startedAt, Instant.now());
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private String errorCategory(Throwable failure) {
        return failure == null
                ? "none"
                : GenerationErrorClassifier.classify(failure).category();
    }
}
