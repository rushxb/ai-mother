package com.rush.rushaicodemother.orchestration.router;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 生成路由遥测的固定配置。
 */
@Data
@Component
@Validated
public class GenerationRoutingTelemetryProperties {

    public static final int TASK_SAMPLE_LIMIT = 20;
    public static final int MINIMUM_TASK_SAMPLES = 4;
    public static final double HIGH_FAILURE_RATE = 0.5;
    public static final int MINIMUM_FEEDBACK_SAMPLES = 2;
    public static final double HIGH_LOW_RATING_RATE = 0.5;
    public static final double HIGH_LOAD_RATIO = 0.8;
    public static final Duration SLOW_AVERAGE_DURATION = Duration.ofMinutes(10);
    public static final Duration CACHE_TTL = Duration.ofSeconds(30);
    public static final Duration COLD_LOAD_TIMEOUT = Duration.ofMillis(100);
    public static final Duration STALE_RETENTION = Duration.ofMinutes(10);
    public static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    public static final int MAX_CACHED_APPLICATIONS = 10_000;
    public static final int MAX_CONCURRENT_LOADS = 4;

    @Min(3)
    @Max(100)
    private int taskSampleLimit = TASK_SAMPLE_LIMIT;

    @Min(2)
    @Max(100)
    private int minimumTaskSamples = MINIMUM_TASK_SAMPLES;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double highFailureRate = HIGH_FAILURE_RATE;

    @Min(1)
    @Max(100)
    private int minimumFeedbackSamples = MINIMUM_FEEDBACK_SAMPLES;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double highLowRatingRate = HIGH_LOW_RATING_RATE;

    @DecimalMin("0.0")
    @DecimalMax("2.0")
    private double highLoadRatio = HIGH_LOAD_RATIO;

    private Duration slowAverageDuration = SLOW_AVERAGE_DURATION;

    private Duration cacheTtl = CACHE_TTL;

    private Duration coldLoadTimeout = COLD_LOAD_TIMEOUT;

    private Duration staleRetention = STALE_RETENTION;

    private Duration shutdownTimeout = SHUTDOWN_TIMEOUT;

    @Min(10)
    @Max(100000)
    private int maxCachedApplications = MAX_CACHED_APPLICATIONS;

    @Min(1)
    @Max(64)
    private int maxConcurrentLoads = MAX_CONCURRENT_LOADS;

    @AssertTrue(message = "生成路由遥测时间配置无效")
    public boolean isDurationConfigurationValid() {
        return slowAverageDuration != null && !slowAverageDuration.isZero() && !slowAverageDuration.isNegative()
                && cacheTtl != null && !cacheTtl.isZero() && !cacheTtl.isNegative()
                && coldLoadTimeout != null && !coldLoadTimeout.isZero() && !coldLoadTimeout.isNegative()
                && staleRetention != null && !staleRetention.isZero() && !staleRetention.isNegative()
                && shutdownTimeout != null && !shutdownTimeout.isZero() && !shutdownTimeout.isNegative()
                && coldLoadTimeout.compareTo(cacheTtl) < 0
                && staleRetention.compareTo(cacheTtl) > 0;
    }
}
