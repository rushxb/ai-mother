package com.rush.rushaicodemother.ratelimiter.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 分布式限流器的安全边界与 Redisson 客户端参数。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.rate-limiter")
public class RateLimiterProperties {

    /** 限流 Redis 键前缀；多环境通过 Redis database 编号隔离，不依赖前缀区分。 */
    public static final String KEY_PREFIX = "rate_limit";

    public static final Duration LIMITER_IDLE_TTL = Duration.ofHours(1);
    public static final int FORWARDED_HEADER_MAX_LENGTH = 4096;
    public static final int FORWARDED_FOR_MAX_HOPS = 32;
    public static final int CONNECTION_MINIMUM_IDLE_SIZE = 1;
    public static final int CONNECTION_POOL_SIZE = 10;
    public static final Duration IDLE_CONNECTION_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);
    public static final int RETRY_ATTEMPTS = 3;
    public static final Duration RETRY_INTERVAL = Duration.ofMillis(1500);

    /**
     * 是否延迟建立 Redisson 连接。默认延迟连接，使进程启动不与 Redis 的瞬时可用性耦合；
     * 实际限流请求仍然使用 Redis，并在 Redis 不可用时失败关闭。
     */
    private boolean lazyInitialization = true;

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9:_-]{0,63}")
    private String keyPrefix = KEY_PREFIX;

    @NotNull
    private Duration limiterIdleTtl = LIMITER_IDLE_TTL;

    /**
     * 允许提供转发头的直接或中间代理 CIDR。默认不信任任何代理。
     */
    @NotNull
    private List<String> trustedProxies = new ArrayList<>();

    @Min(256)
    @Max(16384)
    private int forwardedHeaderMaxLength = FORWARDED_HEADER_MAX_LENGTH;

    @Min(1)
    @Max(128)
    private int forwardedForMaxHops = FORWARDED_FOR_MAX_HOPS;

    @Min(1)
    private int connectionMinimumIdleSize = CONNECTION_MINIMUM_IDLE_SIZE;

    @Min(1)
    private int connectionPoolSize = CONNECTION_POOL_SIZE;

    @NotNull
    private Duration idleConnectionTimeout = IDLE_CONNECTION_TIMEOUT;

    @NotNull
    private Duration connectTimeout = CONNECT_TIMEOUT;

    @NotNull
    private Duration responseTimeout = RESPONSE_TIMEOUT;

    @Min(0)
    @Max(10)
    private int retryAttempts = RETRY_ATTEMPTS;

    @NotNull
    private Duration retryInterval = RETRY_INTERVAL;

    /**
 * 校验当前配置项组合是否合法。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "限流器时长、连接池和可信代理配置无效")
    public boolean isConfigurationValid() {
        return isPositiveAndFitsIntegerMillis(limiterIdleTtl)
                && isPositiveAndFitsIntegerMillis(idleConnectionTimeout)
                && isPositiveAndFitsIntegerMillis(connectTimeout)
                && isPositiveAndFitsIntegerMillis(responseTimeout)
                && isPositiveAndFitsIntegerMillis(retryInterval)
                && connectionPoolSize >= connectionMinimumIdleSize
                && trustedProxies != null
                && trustedProxies.stream().allMatch(value -> value != null && !value.isBlank());
    }

    /** 返回{@code is}正数{@code And}{@code Fits}{@code Integer}对应的毫秒数。 */
    private boolean isPositiveAndFitsIntegerMillis(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return false;
        }
        try {
            return duration.toMillis() <= Integer.MAX_VALUE;
        } catch (ArithmeticException exception) {
            return false;
        }
    }
}
