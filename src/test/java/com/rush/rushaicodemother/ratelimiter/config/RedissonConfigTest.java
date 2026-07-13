package com.rush.rushaicodemother.ratelimiter.config;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedissonConfigTest {

    private final RedissonConfig redissonConfig = new RedissonConfig();

    @Test
    void shouldReuseBootRedisConnectionAndAllowEmptyPassword() {
        RedisProperties redis = redisProperties();
        redis.setHost("localhost");
        redis.setPort(6380);
        redis.setDatabase(7);
        redis.setUsername("root");
        redis.setPassword("");
        RateLimiterProperties limiter = limiterProperties();

        SingleServerConfig configured = redissonConfig.configureSingleServer(new Config(), redis, limiter);

        assertEquals("redis://localhost:6380", configured.getAddress());
        assertEquals(7, configured.getDatabase());
        assertEquals("root", configured.getUsername());
        assertNull(configured.getPassword());
        assertEquals(2, configured.getConnectionMinimumIdleSize());
        assertEquals(12, configured.getConnectionPoolSize());
        assertEquals(4000, configured.getConnectTimeout());
        assertEquals(2500, configured.getTimeout());
    }

    @Test
    void shouldUseRedissAndFormatIpv6Host() {
        RedisProperties redis = redisProperties();
        redis.setHost("2001:db8::10");
        redis.getSsl().setEnabled(true);

        SingleServerConfig configured =
                redissonConfig.configureSingleServer(new Config(), redis, limiterProperties());

        assertEquals("rediss://[2001:db8::10]:6379", configured.getAddress());
    }

    @Test
    void shouldRejectUnsupportedUrlInsteadOfSilentlyIgnoringIt() {
        RedisProperties redis = redisProperties();
        redis.setUrl("redis://localhost:6379");

        assertThrows(
                IllegalArgumentException.class,
                () -> redissonConfig.createConfig(redis, limiterProperties())
        );
    }

    @Test
    void shouldCreateSingleServerConfigForDefaultBootProperties() {
        Config config = redissonConfig.createConfig(redisProperties(), limiterProperties());

        assertTrue(config.isSingleConfig());
    }

    private RedisProperties redisProperties() {
        RedisProperties redis = new RedisProperties();
        redis.setHost("localhost");
        redis.setPort(6379);
        return redis;
    }

    private RateLimiterProperties limiterProperties() {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setConnectionMinimumIdleSize(2);
        properties.setConnectionPoolSize(12);
        properties.setConnectTimeout(Duration.ofSeconds(4));
        properties.setResponseTimeout(Duration.ofMillis(2500));
        return properties;
    }
}
