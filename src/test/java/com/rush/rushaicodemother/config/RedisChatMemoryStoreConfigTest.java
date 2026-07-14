package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.infrastructure.redis.FailoverChatMemoryStore;
import com.rush.rushaicodemother.infrastructure.redis.OptionalRedisOperationMonitor;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisChatMemoryStoreConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RedisChatMemoryStoreConfig.class)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(OptionalRedisOperationMonitor.class, () -> mock(OptionalRedisOperationMonitor.class));

    @Test
    void shouldCreateProjectOwnedFailoverStoreWithStableBeanName() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ChatMemoryStore.class);
            assertThat(context).hasBean("redisChatMemoryStore");
            assertThat(context.getBean("redisChatMemoryStore"))
                    .isInstanceOf(FailoverChatMemoryStore.class);
        });
    }

    @Test
    void shouldRejectInvalidChatMemoryConfiguration() {
        contextRunner
                .withPropertyValues("app.chat-memory.ttl-seconds=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
