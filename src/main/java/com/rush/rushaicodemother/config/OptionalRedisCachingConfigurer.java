package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.infrastructure.redis.OptionalRedisCacheErrorHandler;
import com.rush.rushaicodemother.infrastructure.redis.OptionalRedisOperationMonitor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

/**
 * 为非关键 Spring Cache 注册 fail-open 错误边界。
 *
 * <p>监控组件通过 {@code MeterBinder} 在指标注册表就绪后绑定，不直接依赖
 * {@code MeterRegistry}，因此这里可以保持显式构造注入，也不会提前创建 Redis 连接基础设施。</p>
 */
@Configuration(proxyBeanMethods = false)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
@RequiredArgsConstructor
public class OptionalRedisCachingConfigurer implements CachingConfigurer {

    private final OptionalRedisOperationMonitor monitor;

    @Override
    public CacheErrorHandler errorHandler() {
        return new OptionalRedisCacheErrorHandler(monitor);
    }
}
