package com.rush.rushaicodemother.orchestration.runtime.task;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 本地生成任务执行器适配器的固定容量和关闭控制。 */
@Data
@Component
@Validated
public class GenerationTaskExecutorProperties {

    public static final int MAX_CONCURRENCY = 4;
    public static final int QUEUE_CAPACITY = 32;
    public static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration QUEUE_POLICY_CHECK_INTERVAL = Duration.ofMillis(250);

    @Min(1)
    @Max(512)
    private int maxConcurrency = MAX_CONCURRENCY;

    @Min(1)
    @Max(10000)
    private int queueCapacity = QUEUE_CAPACITY;

    private Duration shutdownTimeout = SHUTDOWN_TIMEOUT;

    /** 排队任务重新检查取消之前的最大间隔及其绝对期限。 */
    private Duration queuePolicyCheckInterval = QUEUE_POLICY_CHECK_INTERVAL;

    @AssertTrue(message = "生成任务执行器关闭超时必须大于 0")
    public boolean isShutdownTimeoutValid() {
        return shutdownTimeout != null && !shutdownTimeout.isZero() && !shutdownTimeout.isNegative();
    }

    @AssertTrue(message = "生成任务队列策略检查间隔必须大于 0")
    public boolean isQueuePolicyCheckIntervalValid() {
        return queuePolicyCheckInterval != null
                && !queuePolicyCheckInterval.isZero()
                && !queuePolicyCheckInterval.isNegative();
    }
}
