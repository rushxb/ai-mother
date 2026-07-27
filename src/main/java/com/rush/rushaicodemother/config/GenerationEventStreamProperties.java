package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 生成事件流配置属性。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-event-stream")
public class GenerationEventStreamProperties {

    private static final Duration MIN_DELTA_FLUSH_INTERVAL = Duration.ofMillis(10);
    private static final Duration MAX_DELTA_FLUSH_INTERVAL = Duration.ofSeconds(1);

    @NotBlank
    private String transport = "local";

    /** 缓存键前缀。 */
    @NotBlank
    private String keyPrefix = "generation:events:";

    /** 数据保留时长。 */
    private Duration retention = Duration.ofHours(2);

    /** 轮询间隔。 */
    private Duration pollInterval = Duration.ofMillis(500);

    @Min(10)
    @Max(5000)
    private int maxEventsPerTask = 500;

    /** 单批读取数量。 */
    @Min(1)
    @Max(500)
    private int readBatchSize = 100;

    @Min(10)
    @Max(100000)
    private int maxTrackedTasks = 1000;

    private boolean deltaCoalescingEnabled = true;

    private Duration deltaFlushInterval = Duration.ofMillis(40);

    @Min(64)
    @Max(65536)
    private int deltaMaxChars = 2048;

    @AssertTrue(message = "生成事件流时长配置无效")
    public boolean isDurationConfigurationValid() {
        return isPositive(retention)
                && isPositive(pollInterval)
                && isPositive(deltaFlushInterval)
                && deltaFlushInterval.compareTo(MIN_DELTA_FLUSH_INTERVAL) >= 0
                && deltaFlushInterval.compareTo(MAX_DELTA_FLUSH_INTERVAL) <= 0;
    }

    private boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
