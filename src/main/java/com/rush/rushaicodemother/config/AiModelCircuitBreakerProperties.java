package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * AI 模型熔断器的固定配置属性。
 */
@Data
@Component
@Validated
public class AiModelCircuitBreakerProperties {

    public static final int FAILURE_THRESHOLD = 1;
    public static final Duration OPEN_DURATION = Duration.ofSeconds(30);
    public static final int MAX_TRACKED_MODELS = 200;

    @Min(1)
    @Max(20)
    private int failureThreshold = FAILURE_THRESHOLD;
    private Duration openDuration = OPEN_DURATION;
    @Min(10)
    @Max(10000)
    private int maxTrackedModels = MAX_TRACKED_MODELS;

    @AssertTrue(message = "AI 模型熔断器打开时长必须大于 0")
    public boolean isOpenDurationPositive() {
        return openDuration != null && !openDuration.isZero() && !openDuration.isNegative();
    }
}
