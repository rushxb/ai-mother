package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** AI Agent 连续模型回合的生产率治理参数。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai-agent-productivity")
public class AiAgentProductivityProperties {

    @Min(100)
    @Max(100_000)
    private int maximumTrackedTasks = 10_000;

    private Duration retention = Duration.ofHours(2);

    @Min(2)
    @Max(64)
    private int maxReadOnlyCallsWithoutMutation = 8;

    @Min(1)
    @Max(16)
    private int maxModelTurnsWithoutMutation = 3;

    @Min(1)
    @Max(8)
    private int forcedActionTurnsBeforeFinalize = 2;

    @AssertTrue(message = "AI Agent 生产率治理配置无效")
    public boolean isConfigurationValid() {
        return retention != null && !retention.isZero() && !retention.isNegative();
    }
}
