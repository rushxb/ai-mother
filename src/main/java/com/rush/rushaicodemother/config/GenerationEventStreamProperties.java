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

    /** 生成事件流 Redis 键前缀；多环境通过 Redis database 编号隔离，不依赖前缀区分。 */
    public static final String KEY_PREFIX = "generation:events:";

    public static final Duration RETENTION = Duration.ofHours(2);
    public static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    public static final int MAX_EVENTS_PER_TASK = 500;
    public static final int READ_BATCH_SIZE = 100;
    public static final int MAX_TRACKED_TASKS = 1000;
    public static final Duration DELTA_FLUSH_INTERVAL = Duration.ofMillis(40);
    public static final int DELTA_MAX_CHARS = 2048;

    @NotBlank
    private String transport = "local";

    /** 缓存键前缀。 */
    @NotBlank
    private String keyPrefix = KEY_PREFIX;

    /** 数据保留时长。 */
    private Duration retention = RETENTION;

    /** 轮询间隔。 */
    private Duration pollInterval = POLL_INTERVAL;

    @Min(10)
    @Max(5000)
    private int maxEventsPerTask = MAX_EVENTS_PER_TASK;

    /** 单批读取数量。 */
    @Min(1)
    @Max(500)
    private int readBatchSize = READ_BATCH_SIZE;

    @Min(10)
    @Max(100000)
    private int maxTrackedTasks = MAX_TRACKED_TASKS;

    private boolean deltaCoalescingEnabled = true;

    private Duration deltaFlushInterval = DELTA_FLUSH_INTERVAL;

    @Min(64)
    @Max(65536)
    private int deltaMaxChars = DELTA_MAX_CHARS;

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
