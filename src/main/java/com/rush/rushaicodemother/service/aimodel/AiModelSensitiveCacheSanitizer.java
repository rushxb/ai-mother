package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.constant.RedisKeyConstant;
import com.rush.rushaicodemother.infrastructure.redis.OptionalRedisOperation;
import com.rush.rushaicodemother.infrastructure.redis.OptionalRedisOperationMonitor;
import com.rush.rushaicodemother.model.event.AiModelConfigChangedEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 清除历史版本可能写入 Redis 的含密钥模型缓存。
 * 新实现不缓存运行时密钥，该守卫用于滚动升级期间阻止旧节点遗留敏感数据。
 */
@Component
@RequiredArgsConstructor
public class AiModelSensitiveCacheSanitizer {

    private final StringRedisTemplate redisTemplate;
    private final OptionalRedisOperationMonitor monitor;

    @PostConstruct
    public void sanitizeAtStartup() {
        deleteSensitiveCache();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onConfigurationChanged(AiModelConfigChangedEvent event) {
        deleteSensitiveCache();
    }

    /** 删除{@code Sensitive}缓存。 */
    private void deleteSensitiveCache() {
        try {
            redisTemplate.delete(RedisKeyConstant.AI_MODEL_ENABLED_LIST);
            monitor.recordSuccess(OptionalRedisOperation.AI_MODEL_SENSITIVE_CACHE_SANITIZE);
        } catch (DataAccessException exception) {
            monitor.recordFailure(OptionalRedisOperation.AI_MODEL_SENSITIVE_CACHE_SANITIZE, exception);
        }
    }
}
