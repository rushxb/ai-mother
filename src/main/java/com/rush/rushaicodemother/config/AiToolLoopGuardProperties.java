package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** AI 工具重复调用和无进展循环的固定治理参数。 */
@Data
@Component
@Validated
public class AiToolLoopGuardProperties {

    public static final int MAXIMUM_TRACKED_TASKS = 10_000;
    public static final Duration RETENTION = Duration.ofHours(2);
    public static final int MAX_IDENTICAL_CALLS = 2;
    public static final int MAX_NO_PROGRESS_CALLS = 6;
    public static final int HISTORY_SIZE = 24;

    @Min(100)
    @Max(100_000)
    private int maximumTrackedTasks = MAXIMUM_TRACKED_TASKS;

    private Duration retention = RETENTION;

    @Min(1)
    @Max(8)
    private int maxIdenticalCalls = MAX_IDENTICAL_CALLS;

    @Min(2)
    @Max(32)
    private int maxNoProgressCalls = MAX_NO_PROGRESS_CALLS;

    @Min(4)
    @Max(128)
    private int historySize = HISTORY_SIZE;

    @AssertTrue(message = "AI 工具循环治理配置无效")
    public boolean isConfigurationValid() {
        return retention != null && !retention.isZero() && !retention.isNegative()
                && historySize >= maxIdenticalCalls
                && historySize >= maxNoProgressCalls;
    }
}
