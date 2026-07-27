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

    private boolean parallelReadsEnabled = false;

    private boolean preparationOverlapEnabled = false;

    @Min(1)
    @Max(64)
    private int maxConcurrentReads = 12;

    @Min(1)
    @Max(64)
    private int maxConcurrentPreparationOverlaps = 4;

    private Duration preparationOverlapTimeout = Duration.ofSeconds(30);

    /** 关闭超时时间。 */
    private Duration shutdownTimeout = Duration.ofSeconds(10);

    @AssertTrue(message = "生成记忆上下文准备重叠超时必须为正数")
    public boolean isPreparationOverlapTimeoutValid() {
        return isPositive(preparationOverlapTimeout);
    }

    @AssertTrue(message = "生成记忆上下文执行器关闭超时必须为正数")
    public boolean isShutdownTimeoutValid() {
        return isPositive(shutdownTimeout);
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
