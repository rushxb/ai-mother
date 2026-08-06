package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 生成工作记忆的固定配置属性。
 */
@Data
@Component
@Validated
public class GenerationWorkingMemoryProperties {

    public static final int MAX_TASKS = 2000;
    public static final Duration RETENTION = Duration.ofHours(2);
    public static final int MAX_RECENT_EVENTS = 100;

    @Min(10)
    @Max(100000)
    private int maxTasks = MAX_TASKS;
    /** 数据保留时长。 */
    private Duration retention = RETENTION;
    @Min(5)
    @Max(500)
    private int maxRecentEvents = MAX_RECENT_EVENTS;

    @AssertTrue(message = "工作记忆保留时长必须大于 0")
    public boolean isRetentionPositive() {
        return retention != null && !retention.isZero() && !retention.isNegative();
    }
}
