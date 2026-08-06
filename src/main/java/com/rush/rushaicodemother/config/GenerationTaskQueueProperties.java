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

    /** 生成任务 Redis Stream 键；多环境通过 Redis database 编号隔离，不依赖键名区分。 */
    public static final String STREAM_KEY = "generation:tasks";

    /** 生成任务消费组名称。 */
    public static final String GROUP = "generation-workers";

    /** 生成任务死信 Stream 键。 */
    public static final String DEAD_LETTER_STREAM_KEY = "generation:tasks:dlq";

    public static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration VISIBILITY_TIMEOUT = Duration.ofSeconds(45);
    public static final Duration DELIVERY_HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    public static final Duration REDISPATCH_INTERVAL = Duration.ofSeconds(15);
    public static final Duration REDISPATCH_AFTER = Duration.ofSeconds(30);
    public static final int READ_BATCH_SIZE = 8;
    public static final int RECLAIM_BATCH_SIZE = 16;
    public static final int MAX_DELIVERY_ATTEMPTS = 5;
    public static final long MAX_STREAM_LENGTH = 100_000;
    public static final int REDISPATCH_BATCH_SIZE = 100;

    @NotBlank
    private String transport = "local";

    @NotBlank
    private String streamKey = STREAM_KEY;

    @NotBlank
    private String group = GROUP;

    @NotBlank
    private String deadLetterStreamKey = DEAD_LETTER_STREAM_KEY;

    private Duration pollTimeout = POLL_TIMEOUT;
    private Duration visibilityTimeout = VISIBILITY_TIMEOUT;
    private Duration deliveryHeartbeatInterval = DELIVERY_HEARTBEAT_INTERVAL;
    private Duration redispatchInterval = REDISPATCH_INTERVAL;
    private Duration redispatchAfter = REDISPATCH_AFTER;

    @Min(1)
    @Max(100)
    private int readBatchSize = READ_BATCH_SIZE;

    @Min(1)
    @Max(100)
    private int reclaimBatchSize = RECLAIM_BATCH_SIZE;

    @Min(1)
    @Max(100)
    private int maxDeliveryAttempts = MAX_DELIVERY_ATTEMPTS;

    @Min(100)
    @Max(1_000_000)
    private long maxStreamLength = MAX_STREAM_LENGTH;

    @Min(1)
    @Max(500)
    private int redispatchBatchSize = REDISPATCH_BATCH_SIZE;

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
