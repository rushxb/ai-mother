package com.rush.rushaicodemother.orchestration.runtime.task;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 本地生成任务执行器适配器的容量和关闭控制。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-task-executor")
public class GenerationTaskExecutorProperties {

    @Min(1)
    @Max(512)
    private int maxConcurrency = 32;

    @Min(1)
    @Max(10000)
    private int queueCapacity = 256;

    private Duration shutdownTimeout = Duration.ofSeconds(30);

    /** 排队任务重新检查取消之前的最大间隔及其绝对期限。 */
    private Duration queuePolicyCheckInterval = Duration.ofMillis(250);

    @AssertTrue(message = "generation task executor shutdown timeout must be positive")
    public boolean isShutdownTimeoutValid() {
        return shutdownTimeout != null && !shutdownTimeout.isZero() && !shutdownTimeout.isNegative();
    }

    @AssertTrue(message = "generation task queue policy check interval must be positive")
    public boolean isQueuePolicyCheckIntervalValid() {
        return queuePolicyCheckInterval != null
                && !queuePolicyCheckInterval.isZero()
                && !queuePolicyCheckInterval.isNegative();
    }
}
