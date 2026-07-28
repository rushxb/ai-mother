package com.rush.rushaicodemother.ratelimiter.aspect;

import com.rush.rushaicodemother.ratelimiter.annotation.RateLimit;
import com.rush.rushaicodemother.ratelimiter.core.DistributedRateLimitEnforcer;
import com.rush.rushaicodemother.ratelimiter.key.RateLimitKeyGenerator;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 限流 AOP 编排层，不承载键解析或 Redis 访问细节。
 */
@Aspect
@Component
public class RateLimitAspect {

    private final RateLimitKeyGenerator keyGenerator;
    private final DistributedRateLimitEnforcer enforcer;

    public RateLimitAspect(RateLimitKeyGenerator keyGenerator, DistributedRateLimitEnforcer enforcer) {
        this.keyGenerator = keyGenerator;
        this.enforcer = enforcer;
    }

    /**
 * 处理{@code enforce}{@code Rate}限制。
 *
 * @param joinPoint {@code joinPoint} 对应的调用参数
 * @param rateLimit {@code rateLimit} 对应的调用参数
 */
    @Before("@annotation(rateLimit)")
    public void enforceRateLimit(JoinPoint joinPoint, RateLimit rateLimit) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> targetClass = joinPoint.getTarget() == null
                ? signature.getDeclaringType()
                : joinPoint.getTarget().getClass();
        Method targetMethod = AopUtils.getMostSpecificMethod(signature.getMethod(), targetClass);
        String key = keyGenerator.generate(targetMethod, rateLimit);
        enforcer.enforce(key, rateLimit);
    }
}
