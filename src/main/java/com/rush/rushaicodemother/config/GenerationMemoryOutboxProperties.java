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

    public static final Duration SCAN_INTERVAL = Duration.ofSeconds(30);
    public static final int BATCH_SIZE = 50;
    public static final int MAX_ATTEMPTS = 10;
    public static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    public static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(30);
    public static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);

    /** 是否启用。 */
    private boolean enabled = true;

    @NotNull
    private Duration scanInterval = SCAN_INTERVAL;

    @Min(1)
    @Max(500)
    private int batchSize = BATCH_SIZE;

    /** 最大尝试次数。 */
    @Min(1)
    @Max(100)
    private int maxAttempts = MAX_ATTEMPTS;

    @NotNull
    private Duration leaseDuration = LEASE_DURATION;

    @NotNull
    private Duration initialRetryDelay = INITIAL_RETRY_DELAY;

    @NotNull
    private Duration maxRetryDelay = MAX_RETRY_DELAY;

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
