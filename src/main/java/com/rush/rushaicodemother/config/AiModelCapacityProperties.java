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

    private boolean enabled;

    /** Redis 键前缀；模型身份在附加之前经过哈希处理。 */
    @NotBlank
    private String keyPrefix = "ai:model:capacity:";

    @Min(1)
    @Max(1000)
    private int maxConcurrentPerModel = 4;

    @Min(1)
    @Max(1_000_000)
    private long requestsPerMinute = 120;

    @Min(1)
    @Max(100_000_000)
    private long tokensPerMinute = 500_000;

    /** 限制输出令牌保留，以便一个请求不能独占整个 TPM 窗口。 */
    @Min(1)
    @Max(1_000_000)
    private int maxReservedOutputTokens = 16_384;

    /** 在尝试故障转移之前，每个准入门允许总等待时间。 */
    private Duration acquireTimeout = Duration.ofMillis(250);

    /** 短期Redis许可证租赁；主动调用会更新它，直到其有界保留截止日期。 */
    private Duration permitLease = Duration.ofSeconds(60);

    /** 共享调度程序心跳间隔；到期前必须留有足够的重试空间。 */
    private Duration heartbeatInterval = Duration.ofSeconds(20);

    /** 当调用者无法提供更窄的上游超时时，绝对安全上限。 */
    private Duration maximumHold = Duration.ofMinutes(16);

    /** 停止续租前，在模型提供方的实际超时时间上增加的宽限期。 */
    private Duration maximumHoldGrace = Duration.ofSeconds(30);

    /** 有界的应用程序范围的调度程序池；从不为每个请求创建一个线程。 */
    @Min(1)
    @Max(16)
    private int schedulerThreads = 2;

    /** 空闲的 Redis 准入密钥会自动删除。 */
    private Duration idleTtl = Duration.ofHours(2);

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
