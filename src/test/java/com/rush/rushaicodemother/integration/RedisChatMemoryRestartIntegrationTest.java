package com.rush.rushaicodemother.integration;

import com.rush.rushaicodemother.infrastructure.redis.SpringRedisChatMemoryStore;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@Tag("integration")
class RedisChatMemoryRestartIntegrationTest {

    private static final String REDIS_HOST = requiredProperty("integration.redis.host");
    private static final int REDIS_PORT = Integer.parseInt(
            requiredProperty("integration.redis.port"));

    @Test
    void secondStoreInstanceMustRestorePendingToolInvocationExactly() {
        LettuceConnectionFactory firstConnection = connectionFactory();
        LettuceConnectionFactory secondConnection = connectionFactory();
        try {
            SpringRedisChatMemoryStore first = new SpringRedisChatMemoryStore(
                    template(firstConnection), "integration:chat:", Duration.ofMinutes(5));
            SpringRedisChatMemoryStore second = new SpringRedisChatMemoryStore(
                    template(secondConnection), "integration:chat:", Duration.ofMinutes(5));
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("call-redis-1")
                    .name("manageSnapshot")
                    .arguments("{\"action\":\"rollbackSnapshot\",\"snapshotName\":\"safe\"}")
                    .build();
            first.updateMessages(11L, List.of(
                    UserMessage.from("rollback to safe"),
                    AiMessage.builder().toolExecutionRequests(List.of(request)).build()
            ));

            var restored = second.getMessages(11L);

            assertEquals(2, restored.size());
            AiMessage assistant = assertInstanceOf(AiMessage.class, restored.get(1));
            ToolExecutionRequest restoredRequest = assistant.toolExecutionRequests().getFirst();
            assertEquals(request.id(), restoredRequest.id());
            assertEquals(request.name(), restoredRequest.name());
            assertEquals(request.arguments(), restoredRequest.arguments());
        } finally {
            firstConnection.destroy();
            secondConnection.destroy();
        }
    }

    private LettuceConnectionFactory connectionFactory() {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                REDIS_HOST, REDIS_PORT);
        connectionFactory.afterPropertiesSet();
        return connectionFactory;
    }

    private StringRedisTemplate template(LettuceConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required integration property is missing: " + name);
        }
        return value.trim();
    }
}
