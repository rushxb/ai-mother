package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.infrastructure.redis.FailoverChatMemoryStore;
import com.rush.rushaicodemother.infrastructure.redis.OptionalRedisOperationMonitor;
import com.rush.rushaicodemother.infrastructure.redis.SpringRedisChatMemoryStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 对话记忆的 Redis 主存储与有界进程内故障转移配置。
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(ChatMemoryProperties.class)
public class RedisChatMemoryStoreConfig {

    private final ChatMemoryProperties properties;

    /**
 * 创建并配置对话记忆存储 Bean。
 *
 * @param redisTemplate Redis 操作模板
 * @param monitor 运行状态监控器
 * @return 配置完成的对话记忆存储 Bean
 */
    @Bean(name = "redisChatMemoryStore")
    public ChatMemoryStore chatMemoryStore(
            StringRedisTemplate redisTemplate,
            OptionalRedisOperationMonitor monitor
    ) {
        ChatMemoryStore primaryStore = new SpringRedisChatMemoryStore(
                redisTemplate,
                properties.getKeyPrefix(),
                Duration.ofSeconds(properties.getTtlSeconds())
        );
        return new FailoverChatMemoryStore(
                primaryStore,
                monitor,
                properties.getFallbackMaxEntries(),
                properties.getFallbackExpireAfterAccess()
        );
    }
}
