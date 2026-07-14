package com.rush.rushaicodemother.infrastructure.redis;

import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

import java.util.Objects;

/**
 * 非关键 Spring Cache 的 fail-open 错误处理器。
 *
 * <p>缓存失败不得回滚已经完成的核心数据库业务；key、value 和缓存名称均不会进入日志或指标。</p>
 */
public class OptionalRedisCacheErrorHandler implements CacheErrorHandler {

    private final OptionalRedisOperationMonitor monitor;

    public OptionalRedisCacheErrorHandler(OptionalRedisOperationMonitor monitor) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
    }

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        monitor.recordFailure(OptionalRedisOperation.SPRING_CACHE_GET, exception);
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        monitor.recordFailure(OptionalRedisOperation.SPRING_CACHE_PUT, exception);
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        monitor.recordFailure(OptionalRedisOperation.SPRING_CACHE_EVICT, exception);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        monitor.recordFailure(OptionalRedisOperation.SPRING_CACHE_CLEAR, exception);
    }
}
