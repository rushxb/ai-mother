package com.rush.rushaicodemother.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RedisClientAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
            .withPropertyValues(
                    "spring.data.redis.host=localhost",
                    "spring.data.redis.port=6379"
            );

    @Test
    void shouldProvideSharedConnectionFactoryAndStringTemplateWithoutConnectingAtStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RedisConnectionFactory.class);
            assertThat(context).hasSingleBean(StringRedisTemplate.class);
        });
    }
}
