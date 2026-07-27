package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** AI 工具重复调用和无进展循环的有界治理参数。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai-tool-loop-guard")
public class AiToolLoopGuardProperties {

    @Min(100)
    @Max(100_000)
    private int maximumTrackedTasks = 10_000;

    private Duration retention = Duration.ofHours(2);

    @Min(1)
    @Max(8)
    private int maxIdenticalCalls = 2;

    @Min(2)
    @Max(32)
    private int maxNoProgressCalls = 6;

    @Min(4)
    @Max(128)
    private int historySize = 24;

    @AssertTrue(message = "AI 工具循环治理配置无效")
    public boolean isConfigurationValid() {
        return retention != null && !retention.isZero() && !retention.isNegative()
                && historySize >= maxIdenticalCalls
                && historySize >= maxNoProgressCalls;
    }
}
