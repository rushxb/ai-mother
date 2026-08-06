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
 * 生成记忆上下文配置属性。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-memory-context")
public class GenerationMemoryContextProperties {

    public static final int MAX_CONCURRENT_READS = 12;
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    public static final int MAX_CONCURRENT_PREPARATION_OVERLAPS = 4;
    public static final Duration PREPARATION_OVERLAP_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private boolean parallelReadsEnabled = false;

    private boolean preparationOverlapEnabled = false;

    @Min(1)
    @Max(64)
    private int maxConcurrentReads = MAX_CONCURRENT_READS;

    private Duration readTimeout = READ_TIMEOUT;

    @Min(1)
    @Max(64)
    private int maxConcurrentPreparationOverlaps = MAX_CONCURRENT_PREPARATION_OVERLAPS;

    private Duration preparationOverlapTimeout = PREPARATION_OVERLAP_TIMEOUT;

    /** 关闭超时时间。 */
    private Duration shutdownTimeout = SHUTDOWN_TIMEOUT;

    @AssertTrue(message = "生成记忆上下文准备重叠超时必须为正数")
    public boolean isPreparationOverlapTimeoutValid() {
        return isPositive(preparationOverlapTimeout);
    }

    @AssertTrue(message = "生成记忆上下文读取超时必须为正数")
    public boolean isReadTimeoutValid() {
        return isPositive(readTimeout);
    }

    @AssertTrue(message = "生成记忆上下文执行器关闭超时必须为正数")
    public boolean isShutdownTimeoutValid() {
        return isPositive(shutdownTimeout);
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
