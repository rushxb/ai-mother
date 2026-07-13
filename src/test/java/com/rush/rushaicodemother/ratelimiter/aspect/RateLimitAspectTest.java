package com.rush.rushaicodemother.ratelimiter.aspect;

import com.rush.rushaicodemother.ratelimiter.annotation.RateLimit;
import com.rush.rushaicodemother.ratelimiter.core.DistributedRateLimitEnforcer;
import com.rush.rushaicodemother.ratelimiter.key.RateLimitKeyGenerator;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitAspectTest {

    @Test
    void shouldOnlyOrchestrateKeyGenerationAndEnforcement() throws Exception {
        RateLimitKeyGenerator keyGenerator = mock(RateLimitKeyGenerator.class);
        DistributedRateLimitEnforcer enforcer = mock(DistributedRateLimitEnforcer.class);
        RateLimitAspect aspect = new RateLimitAspect(keyGenerator, enforcer);
        JoinPoint joinPoint = mock(JoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Target target = new Target();
        Method method = Target.class.getDeclaredMethod("limited");
        RateLimit policy = method.getAnnotation(RateLimit.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(target);
        when(signature.getMethod()).thenReturn(method);
        when(keyGenerator.generate(method, policy)).thenReturn("rate_limit:api:test");

        aspect.enforceRateLimit(joinPoint, policy);

        verify(keyGenerator).generate(method, policy);
        verify(enforcer).enforce("rate_limit:api:test", policy);
    }

    private static final class Target {

        @RateLimit
        void limited() {
        }
    }
}
