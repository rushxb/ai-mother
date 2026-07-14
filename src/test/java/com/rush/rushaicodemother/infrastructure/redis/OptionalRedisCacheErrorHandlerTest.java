package com.rush.rushaicodemother.infrastructure.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OptionalRedisCacheErrorHandlerTest {

    private OptionalRedisOperationMonitor monitor;
    private OptionalRedisCacheErrorHandler errorHandler;
    private Cache cache;
    private RuntimeException failure;

    @BeforeEach
    void setUp() {
        monitor = mock(OptionalRedisOperationMonitor.class);
        errorHandler = new OptionalRedisCacheErrorHandler(monitor);
        cache = mock(Cache.class);
        failure = new RuntimeException("cache backend unavailable");
    }

    @Test
    void shouldFailOpenForAllSpringCacheOperations() {
        assertDoesNotThrow(() -> errorHandler.handleCacheGetError(failure, cache, "secret-key"));
        assertDoesNotThrow(() -> errorHandler.handleCachePutError(failure, cache, "secret-key", "secret-value"));
        assertDoesNotThrow(() -> errorHandler.handleCacheEvictError(failure, cache, "secret-key"));
        assertDoesNotThrow(() -> errorHandler.handleCacheClearError(failure, cache));

        verify(monitor).recordFailure(OptionalRedisOperation.SPRING_CACHE_GET, failure);
        verify(monitor).recordFailure(OptionalRedisOperation.SPRING_CACHE_PUT, failure);
        verify(monitor).recordFailure(OptionalRedisOperation.SPRING_CACHE_EVICT, failure);
        verify(monitor).recordFailure(OptionalRedisOperation.SPRING_CACHE_CLEAR, failure);
    }
}
