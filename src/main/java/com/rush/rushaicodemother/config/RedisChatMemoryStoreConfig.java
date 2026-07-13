package com.rush.rushaicodemother.config;

import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 持久化对话记忆
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(RedisChatMemoryProperties.class)
public class RedisChatMemoryStoreConfig {

    private final RedisChatMemoryProperties properties;

    @Bean
    public RedisChatMemoryStore redisChatMemoryStore() {
        RedisChatMemoryStore.Builder builder = RedisChatMemoryStore.builder()
                .host(properties.getHost())
                .port(properties.getPort())
                .ttl(properties.getTtl());
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            builder.user("default")
                    .password(properties.getPassword());
        }
        return builder.build();
    }
}
