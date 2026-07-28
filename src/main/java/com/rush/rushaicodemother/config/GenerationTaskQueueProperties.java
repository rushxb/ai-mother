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

/** Redis Streams 提供持久生成工作的交付、可见性和死信控制。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-task-queue")
public class GenerationTaskQueueProperties {

    @NotBlank
    private String transport = "local";

    @NotBlank
    private String streamKey = "generation:tasks";

    @NotBlank
    private String group = "generation-workers";

    @NotBlank
    private String deadLetterStreamKey = "generation:tasks:dlq";

    private Duration pollTimeout = Duration.ofSeconds(2);
    private Duration visibilityTimeout = Duration.ofSeconds(45);
    private Duration deliveryHeartbeatInterval = Duration.ofSeconds(10);
    private Duration redispatchInterval = Duration.ofSeconds(15);
    private Duration redispatchAfter = Duration.ofSeconds(30);

    @Min(1)
    @Max(100)
    private int readBatchSize = 8;

    @Min(1)
    @Max(100)
    private int reclaimBatchSize = 16;

    @Min(1)
    @Max(100)
    private int maxDeliveryAttempts = 5;

    @Min(100)
    @Max(1_000_000)
    private long maxStreamLength = 100_000;

    @Min(1)
    @Max(500)
    private int redispatchBatchSize = 100;

    /**
 * 校验各时长配置及其相互约束是否合法。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "generation task queue durations must be positive and visibility must exceed heartbeat")
    public boolean isDurationConfigurationValid() {
        return positive(pollTimeout)
                && positive(visibilityTimeout)
                && positive(deliveryHeartbeatInterval)
                && positive(redispatchInterval)
                && positive(redispatchAfter)
                && deliveryHeartbeatInterval.compareTo(visibilityTimeout) < 0;
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
