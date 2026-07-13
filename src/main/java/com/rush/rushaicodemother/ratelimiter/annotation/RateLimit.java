package com.rush.rushaicodemother.ratelimiter.annotation;

import com.rush.rushaicodemother.ratelimiter.enums.RateLimitType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口的分布式限流策略。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 可选的业务键，用于隔离同一限流维度下的不同策略。
     */
    String key() default "";

    /**
     * 每个时间窗口允许的请求数。
     */
    int rate() default 10;

    /**
     * 时间窗口，单位为秒。
     */
    int rateInterval() default 1;

    /**
     * 限流维度。
     */
    RateLimitType limitType() default RateLimitType.USER;

    /**
     * 触发限流时返回的业务提示。
     */
    String message() default "请求过于频繁，请稍后再试";
}
