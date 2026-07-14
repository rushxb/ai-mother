package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.infrastructure.redis.OptionalRedisCacheErrorHandler;
import com.rush.rushaicodemother.infrastructure.redis.OptionalRedisOperation;
import com.rush.rushaicodemother.infrastructure.redis.OptionalRedisOperationMonitor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OptionalRedisCachingConfigurerTest {

    @Test
    void shouldCreateFailOpenHandlerWithExplicitMonitorDependency() {
        OptionalRedisOperationMonitor monitor = mock(OptionalRedisOperationMonitor.class);
        OptionalRedisCachingConfigurer configurer = new OptionalRedisCachingConfigurer(monitor);

        assertThat(configurer.errorHandler()).isInstanceOf(OptionalRedisCacheErrorHandler.class);
    }

    @Test
    void shouldReportCacheFailureThroughInjectedMonitor() {
        OptionalRedisOperationMonitor monitor = mock(OptionalRedisOperationMonitor.class);
        RuntimeException failure = new RuntimeException("cache unavailable");
        OptionalRedisCachingConfigurer configurer = new OptionalRedisCachingConfigurer(monitor);

        configurer.errorHandler().handleCacheClearError(failure, mock(org.springframework.cache.Cache.class));

        verify(monitor).recordFailure(OptionalRedisOperation.SPRING_CACHE_CLEAR, failure);
    }
}
