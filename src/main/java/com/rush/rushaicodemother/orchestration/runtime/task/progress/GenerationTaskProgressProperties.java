package com.rush.rushaicodemother.orchestration.runtime.task.progress;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 历史分析、缓存和保守的 ETA 回退控制。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-progress")
public class GenerationTaskProgressProperties {

    @Min(10)
    @Max(2_000)
    private int taskSampleLimit = 200;

    @Min(100)
    @Max(20_000)
    private int spanSampleLimit = 5_000;

    @Min(1)
    @Max(1_000)
    private int minimumHistoricalSamples = 8;

    @Min(1)
    @Max(2_000)
    private int highConfidenceSamples = 30;

    @Min(1)
    @Max(500)
    private int maxStageProfiles = 100;

    @Min(1)
    @Max(1_000)
    private int maxCachedRoutes = 100;

    private Duration profileCacheTtl = Duration.ofMinutes(1);

    private Duration fallbackTotalDuration = Duration.ofMinutes(20);

    private Duration maximumEstimatedDuration = Duration.ofHours(2);

    private Duration minimumRunningRemaining = Duration.ofSeconds(5);

    @DecimalMin("1.0")
    private double fallbackP90Multiplier = 1.5d;

    @Min(1)
    @Max(99)
    private int runningProgressCap = 95;

    @AssertTrue(message = "generation progress durations and sample thresholds must be coherent")
    public boolean isConfigurationCoherent() {
        return isPositive(profileCacheTtl)
                && isPositive(fallbackTotalDuration)
                && isPositive(maximumEstimatedDuration)
                && isPositive(minimumRunningRemaining)
                && maximumEstimatedDuration.compareTo(fallbackTotalDuration) >= 0
                && highConfidenceSamples >= minimumHistoricalSamples;
    }

    private boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
