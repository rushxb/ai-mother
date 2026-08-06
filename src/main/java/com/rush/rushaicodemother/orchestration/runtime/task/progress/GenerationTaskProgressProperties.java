package com.rush.rushaicodemother.orchestration.runtime.task.progress;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 历史分析、缓存和保守的 ETA 回退固定控制。 */
@Data
@Component
@Validated
public class GenerationTaskProgressProperties {

    public static final int TASK_SAMPLE_LIMIT = 200;
    public static final int SPAN_SAMPLE_LIMIT = 5_000;
    public static final int MINIMUM_HISTORICAL_SAMPLES = 8;
    public static final int HIGH_CONFIDENCE_SAMPLES = 30;
    public static final int MAX_STAGE_PROFILES = 100;
    public static final int MAX_CACHED_ROUTES = 100;
    public static final Duration PROFILE_CACHE_TTL = Duration.ofMinutes(1);
    public static final Duration FALLBACK_TOTAL_DURATION = Duration.ofMinutes(20);
    public static final Duration MAXIMUM_ESTIMATED_DURATION = Duration.ofHours(2);
    public static final Duration MINIMUM_RUNNING_REMAINING = Duration.ofSeconds(5);
    public static final double FALLBACK_P90_MULTIPLIER = 1.5d;
    public static final int RUNNING_PROGRESS_CAP = 95;

    @Min(10)
    @Max(2_000)
    private int taskSampleLimit = TASK_SAMPLE_LIMIT;

    @Min(100)
    @Max(20_000)
    private int spanSampleLimit = SPAN_SAMPLE_LIMIT;

    @Min(1)
    @Max(1_000)
    private int minimumHistoricalSamples = MINIMUM_HISTORICAL_SAMPLES;

    @Min(1)
    @Max(2_000)
    private int highConfidenceSamples = HIGH_CONFIDENCE_SAMPLES;

    @Min(1)
    @Max(500)
    private int maxStageProfiles = MAX_STAGE_PROFILES;

    @Min(1)
    @Max(1_000)
    private int maxCachedRoutes = MAX_CACHED_ROUTES;

    private Duration profileCacheTtl = PROFILE_CACHE_TTL;

    private Duration fallbackTotalDuration = FALLBACK_TOTAL_DURATION;

    private Duration maximumEstimatedDuration = MAXIMUM_ESTIMATED_DURATION;

    private Duration minimumRunningRemaining = MINIMUM_RUNNING_REMAINING;

    @DecimalMin("1.0")
    private double fallbackP90Multiplier = FALLBACK_P90_MULTIPLIER;

    @Min(1)
    @Max(99)
    private int runningProgressCap = RUNNING_PROGRESS_CAP;

    /**
 * 判断配置{@code Coherent}是否满足约束。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
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
