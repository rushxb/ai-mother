package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * AI 模型熔断器配置属性。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai-model-circuit-breaker")
public class AiModelCircuitBreakerProperties {
    @Min(1)
    @Max(20)
    private int failureThreshold = 1;
    private Duration openDuration = Duration.ofSeconds(30);
    @Min(10)
    @Max(10000)
    private int maxTrackedModels = 200;

    @AssertTrue(message = "AI model circuit breaker open duration must be positive")
    public boolean isOpenDurationPositive() {
        return openDuration != null && !openDuration.isZero() && !openDuration.isNegative();
    }
}
