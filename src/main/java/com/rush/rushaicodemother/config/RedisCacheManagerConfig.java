package com.rush.rushaicodemother.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis 缓存管理器配置
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(RedisCacheProperties.class)
public class RedisCacheManagerConfig {

    private final RedisConnectionFactory redisConnectionFactory;

    private final RedisCacheProperties properties;

    /**
 * 创建并配置缓存管理器 Bean。
 *
 * @return 配置完成的缓存管理器 Bean
 */
    @Bean
    public CacheManager cacheManager() {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(properties.getDefaultTtl())
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()));

        Map<String, RedisCacheConfiguration> cacheConfigurations = new LinkedHashMap<>();
        properties.getCacheTtl().forEach((cacheName, ttl) ->
                cacheConfigurations.put(cacheName, defaultConfig.entryTtl(ttl)));

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }
}
