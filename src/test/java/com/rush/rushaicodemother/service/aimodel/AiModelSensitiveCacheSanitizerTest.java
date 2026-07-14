package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.constant.RedisKeyConstant;
import com.rush.rushaicodemother.infrastructure.redis.OptionalRedisOperation;
import com.rush.rushaicodemother.infrastructure.redis.OptionalRedisOperationMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelSensitiveCacheSanitizerTest {

    private StringRedisTemplate redisTemplate;
    private OptionalRedisOperationMonitor monitor;
    private AiModelSensitiveCacheSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        monitor = mock(OptionalRedisOperationMonitor.class);
        sanitizer = new AiModelSensitiveCacheSanitizer(redisTemplate, monitor);
    }

    @Test
    void shouldRecordSuccessfulSanitization() {
        sanitizer.sanitizeAtStartup();

        verify(redisTemplate).delete(RedisKeyConstant.AI_MODEL_ENABLED_LIST);
        verify(monitor).recordSuccess(OptionalRedisOperation.AI_MODEL_SENSITIVE_CACHE_SANITIZE);
    }

    @Test
    void shouldFailOpenWhenRedisIsUnavailable() {
        RedisConnectionFailureException failure = new RedisConnectionFailureException(
                "redis://default:super-secret@localhost:6379/0"
        );
        when(redisTemplate.delete(RedisKeyConstant.AI_MODEL_ENABLED_LIST)).thenThrow(failure);

        assertDoesNotThrow(sanitizer::sanitizeAtStartup);

        verify(monitor).recordFailure(
                OptionalRedisOperation.AI_MODEL_SENSITIVE_CACHE_SANITIZE,
                failure
        );
    }
}
