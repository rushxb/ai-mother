package com.rush.rushaicodemother.infrastructure.redis;

/**
 * 可降级 Redis 操作的固定分类。
 *
 * <p>枚举值同时限定监控标签基数，禁止把缓存名称、业务 ID 或 Redis 地址写入指标标签。</p>
 */
public enum OptionalRedisOperation {

    AI_MODEL_SENSITIVE_CACHE_SANITIZE("ai_model_sensitive_cache_sanitize"),
    SPRING_CACHE_GET("spring_cache_get"),
    SPRING_CACHE_PUT("spring_cache_put"),
    SPRING_CACHE_EVICT("spring_cache_evict"),
    SPRING_CACHE_CLEAR("spring_cache_clear"),
    CHAT_MEMORY_GET("chat_memory_get"),
    CHAT_MEMORY_UPDATE("chat_memory_update"),
    CHAT_MEMORY_DELETE("chat_memory_delete");

    private final String metricTag;

    OptionalRedisOperation(String metricTag) {
        this.metricTag = metricTag;
    }

    public String metricTag() {
        return metricTag;
    }
}
