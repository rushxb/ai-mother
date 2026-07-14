package com.rush.rushaicodemother.infrastructure.redis;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Role;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 记录非关键 Redis 能力的降级与恢复状态。
 *
 * <p>日志只包含固定操作名和异常类型。连续故障只在首次出现时告警，恢复后再次故障才重新告警，
 * 避免 Redis 不可用时产生日志洪泛或泄露连接信息。</p>
 */
@Slf4j
@Component
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class OptionalRedisOperationMonitor implements MeterBinder {

    private static final String METRIC_NAME = "redis_optional_operations_total";
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_FAILURE = "failure";

    private final LongAdder[][] operationCounters = createOperationCounters();
    private final ConcurrentMap<OptionalRedisOperation, AtomicBoolean> degradedStates = new ConcurrentHashMap<>();

    @Override
    public void bindTo(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (OptionalRedisOperation operation : OptionalRedisOperation.values()) {
            registerCounter(registry, operation, RESULT_SUCCESS, successCounter(operation));
            registerCounter(registry, operation, RESULT_FAILURE, failureCounter(operation));
        }
    }

    public void recordSuccess(OptionalRedisOperation operation) {
        OptionalRedisOperation requiredOperation = Objects.requireNonNull(operation, "operation");
        increment(requiredOperation, RESULT_SUCCESS);
        AtomicBoolean degraded = degradedStates.computeIfAbsent(
                requiredOperation,
                ignored -> new AtomicBoolean(false)
        );
        if (degraded.getAndSet(false)) {
            log.info("Optional Redis operation recovered: operation={}", requiredOperation.metricTag());
        }
    }

    public void recordFailure(OptionalRedisOperation operation, RuntimeException failure) {
        OptionalRedisOperation requiredOperation = Objects.requireNonNull(operation, "operation");
        RuntimeException requiredFailure = Objects.requireNonNull(failure, "failure");
        increment(requiredOperation, RESULT_FAILURE);
        AtomicBoolean degraded = degradedStates.computeIfAbsent(
                requiredOperation,
                ignored -> new AtomicBoolean(false)
        );
        if (degraded.compareAndSet(false, true)) {
            log.warn(
                    "Optional Redis operation degraded: operation={}, exceptionType={}",
                    requiredOperation.metricTag(),
                    requiredFailure.getClass().getSimpleName()
            );
        }
    }

    private void increment(OptionalRedisOperation operation, String result) {
        counter(operation, result).increment();
    }

    private LongAdder counter(OptionalRedisOperation operation, String result) {
        return RESULT_SUCCESS.equals(result) ? successCounter(operation) : failureCounter(operation);
    }

    private LongAdder successCounter(OptionalRedisOperation operation) {
        return operationCounters[operation.ordinal()][0];
    }

    private LongAdder failureCounter(OptionalRedisOperation operation) {
        return operationCounters[operation.ordinal()][1];
    }

    private void registerCounter(
            MeterRegistry registry,
            OptionalRedisOperation operation,
            String result,
            LongAdder counter
    ) {
        FunctionCounter.builder(METRIC_NAME, counter, LongAdder::sum)
                .tag("operation", operation.metricTag())
                .tag("result", result)
                .register(registry);
    }

    private static LongAdder[][] createOperationCounters() {
        OptionalRedisOperation[] operations = OptionalRedisOperation.values();
        LongAdder[][] counters = new LongAdder[operations.length][2];
        for (int operationIndex = 0; operationIndex < operations.length; operationIndex++) {
            counters[operationIndex][0] = new LongAdder();
            counters[operationIndex][1] = new LongAdder();
        }
        return counters;
    }
}
