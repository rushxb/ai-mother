package com.rush.rushaicodemother.ratelimiter.core;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.ratelimiter.annotation.RateLimit;
import com.rush.rushaicodemother.ratelimiter.config.RateLimiterProperties;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateLimiterConfig;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributedRateLimitEnforcerTest {

    private static final String KEY = "rate_limit:user:42";

    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RRateLimiter rateLimiter = mock(RRateLimiter.class);
    private final RateLimiterProperties properties = new RateLimiterProperties();
    private final DistributedRateLimitEnforcer enforcer =
            new DistributedRateLimitEnforcer(redissonClient, properties);

    @Test
    void shouldInitializeBeforeSettingTtlAndAcquiringToken() {
        RateLimit policy = policy();
        when(redissonClient.getRateLimiter(KEY)).thenReturn(rateLimiter);
        when(rateLimiter.trySetRate(RateType.OVERALL, 5, Duration.ofSeconds(60))).thenReturn(true);
        when(rateLimiter.tryAcquire()).thenReturn(true);

        enforcer.enforce(KEY, policy);

        InOrder order = inOrder(rateLimiter);
        order.verify(rateLimiter).trySetRate(RateType.OVERALL, 5, Duration.ofSeconds(60));
        order.verify(rateLimiter).expire(properties.getLimiterIdleTtl());
        order.verify(rateLimiter).tryAcquire();
        verify(rateLimiter, never()).getConfig();
        verify(rateLimiter, never()).setRate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(Duration.class)
        );
    }

    @Test
    void shouldNotResetLimiterWhenExistingConfigurationMatches() {
        RateLimit policy = policy();
        when(redissonClient.getRateLimiter(KEY)).thenReturn(rateLimiter);
        when(rateLimiter.trySetRate(RateType.OVERALL, 5, Duration.ofSeconds(60))).thenReturn(false);
        when(rateLimiter.getConfig()).thenReturn(new RateLimiterConfig(RateType.OVERALL, 60_000L, 5L));
        when(rateLimiter.tryAcquire()).thenReturn(true);

        enforcer.enforce(KEY, policy);

        verify(rateLimiter, never()).setRate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(Duration.class)
        );
    }

    @Test
    void shouldUpdateLimiterOnlyWhenAnnotationConfigurationChanges() {
        RateLimit policy = policy();
        when(redissonClient.getRateLimiter(KEY)).thenReturn(rateLimiter);
        when(rateLimiter.trySetRate(RateType.OVERALL, 5, Duration.ofSeconds(60))).thenReturn(false);
        when(rateLimiter.getConfig()).thenReturn(new RateLimiterConfig(RateType.OVERALL, 30_000L, 10L));
        when(rateLimiter.tryAcquire()).thenReturn(true);

        enforcer.enforce(KEY, policy);

        verify(rateLimiter).setRate(RateType.OVERALL, 5, Duration.ofSeconds(60));
    }

    @Test
    void shouldReturnConfiguredBusinessMessageWhenTokenIsRejected() {
        RateLimit policy = policy();
        when(redissonClient.getRateLimiter(KEY)).thenReturn(rateLimiter);
        when(rateLimiter.trySetRate(RateType.OVERALL, 5, Duration.ofSeconds(60))).thenReturn(true);
        when(rateLimiter.tryAcquire()).thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> enforcer.enforce(KEY, policy)
        );

        assertEquals(ErrorCode.TOO_MANY_REQUEST.getCode(), exception.getCode());
        assertEquals("测试限流提示", exception.getMessage());
    }

    private RateLimit policy() {
        try {
            Method method = PolicyHolder.class.getDeclaredMethod("limited");
            return method.getAnnotation(RateLimit.class);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class PolicyHolder {

        @RateLimit(rate = 5, rateInterval = 60, message = "测试限流提示")
        void limited() {
        }
    }
}
