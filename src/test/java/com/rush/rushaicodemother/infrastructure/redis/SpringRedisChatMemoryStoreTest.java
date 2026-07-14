package com.rush.rushaicodemother.infrastructure.redis;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringRedisChatMemoryStoreTest {

    private static final Duration TTL = Duration.ofHours(1);

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private SpringRedisChatMemoryStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new SpringRedisChatMemoryStore(redisTemplate, "chat-memory:", TTL);
    }

    @Test
    void shouldReturnEmptyMessagesWhenRedisValueIsMissingOrBlank() {
        when(valueOperations.get("chat-memory:42")).thenReturn(null, " ");

        assertEquals(List.of(), store.getMessages(42L));
        assertEquals(List.of(), store.getMessages(42L));
    }

    @Test
    void shouldDeserializeMessagesFromStandardRedisString() {
        List<ChatMessage> messages = messages("hello");
        when(valueOperations.get("chat-memory:42"))
                .thenReturn(ChatMessageSerializer.messagesToJson(messages));

        assertEquals(messages, store.getMessages(42L));
    }

    @Test
    void shouldSerializeMessagesWithNamespaceAndTtl() {
        List<ChatMessage> messages = messages("hello");
        String serializedMessages = ChatMessageSerializer.messagesToJson(messages);

        store.updateMessages(42L, messages);

        verify(valueOperations).set("chat-memory:42", serializedMessages, TTL);
    }

    @Test
    void shouldDeleteNamespacedKey() {
        store.deleteMessages(42L);

        verify(redisTemplate).delete("chat-memory:42");
    }

    @Test
    void shouldRejectInvalidMemoryIdsAndMessagesBeforeCallingRedis() {
        assertThrows(IllegalArgumentException.class, () -> store.getMessages(null));
        assertThrows(IllegalArgumentException.class, () -> store.getMessages(" "));
        assertThrows(IllegalArgumentException.class, () -> store.getMessages("x".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> store.updateMessages(42L, null));
        assertThrows(IllegalArgumentException.class, () -> store.updateMessages(42L, List.of()));
    }

    @Test
    void shouldPropagateMalformedStoredData() {
        when(valueOperations.get("chat-memory:42")).thenReturn("not-json");

        assertThrows(RuntimeException.class, () -> store.getMessages(42L));
    }

    @Test
    void shouldRejectInvalidStoreConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SpringRedisChatMemoryStore(redisTemplate, " ", TTL)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SpringRedisChatMemoryStore(redisTemplate, "chat-memory:", Duration.ZERO)
        );
    }

    private List<ChatMessage> messages(String text) {
        return List.of(UserMessage.from(text));
    }
}
