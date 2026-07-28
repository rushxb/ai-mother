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

    /**
     * 是否延迟建立 Redisson 连接。默认延迟连接，使进程启动不与 Redis 的瞬时可用性耦合；
     * 实际限流请求仍然使用 Redis，并在 Redis 不可用时失败关闭。
     */
    private boolean lazyInitialization = true;

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9:_-]{0,63}")
    private String keyPrefix = "rate_limit";

    @NotNull
    private Duration limiterIdleTtl = Duration.ofHours(1);

    /**
     * 允许提供转发头的直接或中间代理 CIDR。默认不信任任何代理。
     */
    @NotNull
    private List<String> trustedProxies = new ArrayList<>();

    @Min(256)
    @Max(16384)
    private int forwardedHeaderMaxLength = 4096;

    @Min(1)
    @Max(128)
    private int forwardedForMaxHops = 32;

    @Min(1)
    private int connectionMinimumIdleSize = 1;

    @Min(1)
    private int connectionPoolSize = 10;

    @NotNull
    private Duration idleConnectionTimeout = Duration.ofSeconds(30);

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(5);

    @NotNull
    private Duration responseTimeout = Duration.ofSeconds(3);

    @Min(0)
    @Max(10)
    private int retryAttempts = 3;

    @NotNull
    private Duration retryInterval = Duration.ofMillis(1500);

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
