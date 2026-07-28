package com.rush.rushaicodemother.orchestration.router;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 生成路由遥测配置属性。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-routing.telemetry")
public class GenerationRoutingTelemetryProperties {

    @Min(3)
    @Max(100)
    private int taskSampleLimit = 20;

    @Min(2)
    @Max(100)
    private int minimumTaskSamples = 4;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double highFailureRate = 0.5;

    @Min(1)
    @Max(100)
    private int minimumFeedbackSamples = 2;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double highLowRatingRate = 0.5;

    @DecimalMin("0.0")
    @DecimalMax("2.0")
    private double highLoadRatio = 0.8;

    private Duration slowAverageDuration = Duration.ofMinutes(10);

    private Duration cacheTtl = Duration.ofSeconds(30);

    private Duration coldLoadTimeout = Duration.ofMillis(100);

    private Duration staleRetention = Duration.ofMinutes(10);

    private Duration shutdownTimeout = Duration.ofSeconds(5);

    @Min(10)
    @Max(100000)
    private int maxCachedApplications = 10000;

    @Min(1)
    @Max(64)
    private int maxConcurrentLoads = 4;

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
