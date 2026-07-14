package com.rush.rushaicodemother.ratelimiter.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.ConstantDelay;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 使用 Spring Boot Redis 连接配置创建限流专用 Redisson 客户端。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RedisProperties.class, RateLimiterProperties.class})
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            RedisProperties redisProperties,
            RateLimiterProperties rateLimiterProperties
    ) {
        return Redisson.create(createConfig(redisProperties, rateLimiterProperties));
    }

    Config createConfig(RedisProperties redisProperties, RateLimiterProperties rateLimiterProperties) {
        rejectUnsupportedTopology(redisProperties);
        Config config = new Config();
        config.setLazyInitialization(rateLimiterProperties.isLazyInitialization());
        configureSingleServer(config, redisProperties, rateLimiterProperties);
        return config;
    }

    SingleServerConfig configureSingleServer(
            Config config,
            RedisProperties redisProperties,
            RateLimiterProperties rateLimiterProperties
    ) {
        if (!StringUtils.hasText(redisProperties.getHost())) {
            throw new IllegalArgumentException("spring.data.redis.host 不能为空");
        }
        String scheme = redisProperties.getSsl().isEnabled() ? "rediss://" : "redis://";
        String address = scheme + formatHost(redisProperties.getHost().trim()) + ":" + redisProperties.getPort();

        SingleServerConfig singleServer = config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisProperties.getDatabase())
                .setConnectionMinimumIdleSize(rateLimiterProperties.getConnectionMinimumIdleSize())
                .setConnectionPoolSize(rateLimiterProperties.getConnectionPoolSize())
                .setIdleConnectionTimeout(toIntMillis(rateLimiterProperties.getIdleConnectionTimeout()))
                .setConnectTimeout(toIntMillis(rateLimiterProperties.getConnectTimeout()))
                .setTimeout(toIntMillis(rateLimiterProperties.getResponseTimeout()))
                .setRetryAttempts(rateLimiterProperties.getRetryAttempts())
                .setRetryDelay(new ConstantDelay(rateLimiterProperties.getRetryInterval()));

        if (StringUtils.hasText(redisProperties.getUsername())) {
            singleServer.setUsername(redisProperties.getUsername());
        }
        if (StringUtils.hasLength(redisProperties.getPassword())) {
            singleServer.setPassword(redisProperties.getPassword());
        }
        if (StringUtils.hasText(redisProperties.getClientName())) {
            singleServer.setClientName(redisProperties.getClientName());
        }
        return singleServer;
    }

    private void rejectUnsupportedTopology(RedisProperties redisProperties) {
        if (StringUtils.hasText(redisProperties.getUrl())) {
            throw new IllegalArgumentException(
                    "限流 Redisson 客户端不支持 spring.data.redis.url，请使用 host、port、username 和 password"
            );
        }
        if (redisProperties.getCluster() != null || redisProperties.getSentinel() != null) {
            throw new IllegalArgumentException("限流 Redisson 客户端当前仅支持 Redis 单节点配置");
        }
    }

    private String formatHost(String host) {
        if (host.indexOf(':') >= 0 && !(host.startsWith("[") && host.endsWith("]"))) {
            return "[" + host + "]";
        }
        return host;
    }

    private int toIntMillis(java.time.Duration duration) {
        return Math.toIntExact(duration.toMillis());
    }
}
