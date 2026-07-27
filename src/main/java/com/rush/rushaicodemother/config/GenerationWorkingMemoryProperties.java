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
 * 生成工作记忆配置属性。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.memory.working")
public class GenerationWorkingMemoryProperties {
    @Min(10)
    @Max(100000)
    private int maxTasks = 2000;
    /** 数据保留时长。 */
    private Duration retention = Duration.ofHours(2);
    @Min(5)
    @Max(500)
    private int maxRecentEvents = 100;

    @AssertTrue(message = "working memory retention must be positive")
    public boolean isRetentionPositive() {
        return retention != null && !retention.isZero() && !retention.isNegative();
    }
}
