package com.rush.rushaicodemother.infrastructure.redis;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 基于 Spring Data Redis 统一连接工厂的对话记忆主存储。
 *
 * <p>复用应用已有的 Redis host、database、ACL、SSL 和超时配置，避免额外创建绕过 Spring 配置的客户端。</p>
 */
public class SpringRedisChatMemoryStore implements ChatMemoryStore {

    private static final int MAX_MEMORY_ID_LENGTH = 128;

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final Duration ttl;

    public SpringRedisChatMemoryStore(StringRedisTemplate redisTemplate, String keyPrefix, Duration ttl) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.keyPrefix = requireKeyPrefix(keyPrefix);
        this.ttl = requirePositiveDuration(ttl);
    }

    /**
 * 获取并返回消息。
 *
 * @param memoryId 记忆编号
 * @return SpringRedis 对话记忆存储集合
 */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String serializedMessages = redisTemplate.opsForValue().get(redisKey(memoryId));
        if (serializedMessages == null || serializedMessages.isBlank()) {
            return List.of();
        }
        return List.copyOf(ChatMessageDeserializer.messagesFromJson(serializedMessages));
    }

    /**
 * 更新消息。
 *
 * @param memoryId 记忆编号
 * @param messages 消息列表
 */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages cannot be null or empty");
        }
        String serializedMessages = ChatMessageSerializer.messagesToJson(List.copyOf(messages));
        redisTemplate.opsForValue().set(redisKey(memoryId), serializedMessages, ttl);
    }

    /**
 * 删除消息。
 *
 * @param memoryId 记忆编号
 */
    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(redisKey(memoryId));
    }

    /** 返回 Redis 键。 */
    private String redisKey(Object memoryId) {
        if (memoryId == null) {
            throw new IllegalArgumentException("memoryId cannot be null or blank");
        }
        String normalizedMemoryId = memoryId.toString().trim();
        if (normalizedMemoryId.isEmpty() || normalizedMemoryId.length() > MAX_MEMORY_ID_LENGTH) {
            throw new IllegalArgumentException("memoryId must contain 1 to 128 characters");
        }
        return keyPrefix + normalizedMemoryId;
    }

    private String requireKeyPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix cannot be null or blank");
        }
        return prefix;
    }

    private Duration requirePositiveDuration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        return duration;
    }
}
