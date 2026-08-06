package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** AI Agent 连续模型回合的固定生产率治理参数。 */
@Data
@Component
@Validated
public class AiAgentProductivityProperties {

    public static final int MAXIMUM_TRACKED_TASKS = 10_000;
    public static final Duration RETENTION = Duration.ofHours(2);
    public static final int MAX_READ_ONLY_CALLS_WITHOUT_MUTATION = 8;
    public static final int MAX_MODEL_TURNS_WITHOUT_MUTATION = 3;
    public static final int FORCED_ACTION_TURNS_BEFORE_FINALIZE = 2;

    @Min(100)
    @Max(100_000)
    private int maximumTrackedTasks = MAXIMUM_TRACKED_TASKS;

    private Duration retention = RETENTION;

    @Min(2)
    @Max(64)
    private int maxReadOnlyCallsWithoutMutation = MAX_READ_ONLY_CALLS_WITHOUT_MUTATION;

    @Min(1)
    @Max(16)
    private int maxModelTurnsWithoutMutation = MAX_MODEL_TURNS_WITHOUT_MUTATION;

    @Min(1)
    @Max(8)
    private int forcedActionTurnsBeforeFinalize = FORCED_ACTION_TURNS_BEFORE_FINALIZE;

    @AssertTrue(message = "AI Agent 生产率治理配置无效")
    public boolean isConfigurationValid() {
        return retention != null && !retention.isZero() && !retention.isNegative();
    }
}
