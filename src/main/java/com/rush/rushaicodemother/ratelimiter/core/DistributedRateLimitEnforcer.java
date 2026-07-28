package com.rush.rushaicodemother.ratelimiter.core;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.ratelimiter.annotation.RateLimit;
import com.rush.rushaicodemother.ratelimiter.config.RateLimiterProperties;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateLimiterConfig;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * 维护 Redisson 限流器配置并获取请求令牌。
 */
@Component
public class DistributedRateLimitEnforcer {

    private final RedissonClient redissonClient;
    private final Duration limiterIdleTtl;

    public DistributedRateLimitEnforcer(RedissonClient redissonClient, RateLimiterProperties properties) {
        this.redissonClient = redissonClient;
        this.limiterIdleTtl = properties.getLimiterIdleTtl();
    }

    /**
 * 处理{@code enforce}。
 *
 * @param key 键
 * @param policy 策略
 */
    public void enforce(String key, RateLimit policy) {
        validatePolicy(key, policy);
        Duration interval = Duration.ofSeconds(policy.rateInterval());
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);

        boolean initialized = rateLimiter.trySetRate(RateType.OVERALL, policy.rate(), interval);
        if (!initialized && configurationChanged(rateLimiter.getConfig(), policy.rate(), interval)) {
            rateLimiter.setRate(RateType.OVERALL, policy.rate(), interval);
        }

        rateLimiter.expire(limiterIdleTtl);
        if (!rateLimiter.tryAcquire()) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, policy.message());
        }
    }

    private boolean configurationChanged(RateLimiterConfig current, long expectedRate, Duration expectedInterval) {
        return current == null
                || current.getRateType() != RateType.OVERALL
                || !Objects.equals(current.getRate(), expectedRate)
                || !Objects.equals(current.getRateInterval(), expectedInterval.toMillis());
    }

    /** 校验{@code ate}策略是否有效。 */
    private void validatePolicy(String key, RateLimit policy) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "限流键不能为空");
        }
        if (policy == null || policy.rate() <= 0 || policy.rateInterval() <= 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "限流策略必须使用正数请求数和时间窗口");
        }
    }
}
