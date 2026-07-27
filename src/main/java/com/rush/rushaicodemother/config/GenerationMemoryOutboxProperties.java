package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 生成记忆事务发件箱配置属性。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.memory.outbox")
public class GenerationMemoryOutboxProperties {

    /** 是否启用。 */
    private boolean enabled = true;

    @NotNull
    private Duration scanInterval = Duration.ofSeconds(30);

    @Min(1)
    @Max(500)
    private int batchSize = 50;

    /** 最大尝试次数。 */
    @Min(1)
    @Max(100)
    private int maxAttempts = 10;

    @NotNull
    private Duration leaseDuration = Duration.ofMinutes(2);

    @NotNull
    private Duration initialRetryDelay = Duration.ofSeconds(30);

    @NotNull
    private Duration maxRetryDelay = Duration.ofHours(1);

    @AssertTrue(message = "memory outbox durations must be positive and ordered")
    public boolean isDurationPolicyValid() {
        return positive(scanInterval)
                && positive(leaseDuration)
                && positive(initialRetryDelay)
                && positive(maxRetryDelay)
                && !maxRetryDelay.minus(initialRetryDelay).isNegative();
    }

    private boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
