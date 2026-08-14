package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 生成终态副作用 outbox 的有界执行与重试策略。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation.terminal-effects")
public class GenerationTerminalEffectProperties {

    public static final int BATCH_SIZE = 100;
    public static final int MAX_ATTEMPTS = 10;
    public static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    public static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(5);
    public static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);

    @Min(1)
    @Max(500)
    private int batchSize = BATCH_SIZE;

    @Min(1)
    @Max(100)
    private int maxAttempts = MAX_ATTEMPTS;

    @NotNull
    private Duration leaseDuration = LEASE_DURATION;

    @NotNull
    private Duration initialRetryDelay = INITIAL_RETRY_DELAY;

    @NotNull
    private Duration maxRetryDelay = MAX_RETRY_DELAY;

    @AssertTrue(message = "terminal effect durations must be positive and ordered")
    public boolean isDurationPolicyValid() {
        return positive(leaseDuration)
                && positive(initialRetryDelay)
                && positive(maxRetryDelay)
                && !maxRetryDelay.minus(initialRetryDelay).isNegative();
    }

    private boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
