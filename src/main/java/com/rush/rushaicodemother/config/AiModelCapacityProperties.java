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

/** 在每个提供商请求之前应用集群范围的模型准入限制。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai-model-capacity")
public class AiModelCapacityProperties {

    /** 模型容量 Redis 键前缀；多环境通过 Redis database 编号隔离，不依赖前缀区分。 */
    public static final String KEY_PREFIX = "ai:model:capacity:";

    public static final int MAX_CONCURRENT_PER_MODEL = 4;
    public static final long REQUESTS_PER_MINUTE = 120;
    public static final long TOKENS_PER_MINUTE = 500_000;
    public static final int MAX_RESERVED_OUTPUT_TOKENS = 16_384;
    public static final Duration ACQUIRE_TIMEOUT = Duration.ofMillis(250);
    public static final Duration PERMIT_LEASE = Duration.ofSeconds(60);
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(20);
    public static final Duration MAXIMUM_HOLD = Duration.ofMinutes(16);
    public static final Duration MAXIMUM_HOLD_GRACE = Duration.ofSeconds(30);
    public static final int SCHEDULER_THREADS = 2;
    public static final Duration IDLE_TTL = Duration.ofHours(2);

    private boolean enabled;

    /** Redis 键前缀；模型身份在附加之前经过哈希处理。 */
    @NotBlank
    private String keyPrefix = KEY_PREFIX;

    @Min(1)
    @Max(1000)
    private int maxConcurrentPerModel = MAX_CONCURRENT_PER_MODEL;

    @Min(1)
    @Max(1_000_000)
    private long requestsPerMinute = REQUESTS_PER_MINUTE;

    @Min(1)
    @Max(100_000_000)
    private long tokensPerMinute = TOKENS_PER_MINUTE;

    /** 限制输出令牌保留，以便一个请求不能独占整个 TPM 窗口。 */
    @Min(1)
    @Max(1_000_000)
    private int maxReservedOutputTokens = MAX_RESERVED_OUTPUT_TOKENS;

    /** 在尝试故障转移之前，每个准入门允许总等待时间。 */
    private Duration acquireTimeout = ACQUIRE_TIMEOUT;

    /** 短期Redis许可证租赁；主动调用会更新它，直到其有界保留截止日期。 */
    private Duration permitLease = PERMIT_LEASE;

    /** 共享调度程序心跳间隔；到期前必须留有足够的重试空间。 */
    private Duration heartbeatInterval = HEARTBEAT_INTERVAL;

    /** 当调用者无法提供更窄的上游超时时，绝对安全上限。 */
    private Duration maximumHold = MAXIMUM_HOLD;

    /** 停止续租前，在模型提供方的实际超时时间上增加的宽限期。 */
    private Duration maximumHoldGrace = MAXIMUM_HOLD_GRACE;

    /** 有界的应用程序范围的调度程序池；从不为每个请求创建一个线程。 */
    @Min(1)
    @Max(16)
    private int schedulerThreads = SCHEDULER_THREADS;

    /** 空闲的 Redis 准入密钥会自动删除。 */
    private Duration idleTtl = IDLE_TTL;

    /** 可用逃生舱口；生产应保持故障关闭。 */
    private boolean failOpen;

    /**
 * 校验各时长配置及其相互约束是否合法。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "AI model capacity duration configuration is invalid")
    public boolean isDurationConfigurationValid() {
        return atLeastOneMillisecond(acquireTimeout)
                && atLeastOneMillisecond(permitLease)
                && atLeastOneMillisecond(heartbeatInterval)
                && atLeastOneMillisecond(maximumHold)
                && atLeastOneMillisecond(maximumHoldGrace)
                && atLeastOneMillisecond(idleTtl)
                && heartbeatInterval.compareTo(permitLease.dividedBy(2)) <= 0
                && maximumHold.compareTo(permitLease) > 0
                && idleTtl.compareTo(maximumHold) > 0;
    }

    private boolean atLeastOneMillisecond(Duration value) {
        return value != null && !value.isNegative() && value.toMillis() >= 1L;
    }
}
