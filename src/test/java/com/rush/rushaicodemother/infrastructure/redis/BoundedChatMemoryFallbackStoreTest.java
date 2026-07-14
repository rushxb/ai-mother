package com.rush.rushaicodemother.infrastructure.redis;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedChatMemoryFallbackStoreTest {

    @Test
    void shouldExpireOnlySynchronizedCopies() {
        AtomicLong nanoTime = new AtomicLong();
        BoundedChatMemoryFallbackStore store = new BoundedChatMemoryFallbackStore(
                2,
                Duration.ofNanos(10),
                nanoTime::get
        );
        List<ChatMessage> synchronizedMessages = messages("synchronized");
        List<ChatMessage> pendingMessages = messages("pending");
        store.putSynchronizedMessages(1L, synchronizedMessages);
        store.putPendingUpdate(2L, pendingMessages);

        nanoTime.set(11);

        assertNull(store.getIfPresent(1L));
        assertEquals(pendingMessages, store.getIfPresent(2L).messages());
    }

    @Test
    void shouldEvictSynchronizedCopyBeforeAcceptingPendingMutation() {
        BoundedChatMemoryFallbackStore store = new BoundedChatMemoryFallbackStore(
                1,
                Duration.ofHours(1)
        );
        store.putSynchronizedMessages(1L, messages("synchronized"));

        store.putPendingDelete(2L);

        assertNull(store.getIfPresent(1L));
        assertEquals(
                BoundedChatMemoryFallbackStore.MutationType.DELETE,
                store.getIfPresent(2L).pendingMutation()
        );
    }

    @Test
    void shouldRejectOverflowWithoutEvictingExistingPendingMutation() {
        BoundedChatMemoryFallbackStore store = new BoundedChatMemoryFallbackStore(
                1,
                Duration.ofHours(1)
        );
        List<ChatMessage> firstMessages = messages("first");
        store.putPendingUpdate(1L, firstMessages);

        assertThrows(
                ChatMemoryFallbackCapacityExceededException.class,
                () -> store.putPendingUpdate(2L, messages("second"))
        );

        assertEquals(firstMessages, store.getIfPresent(1L).messages());
        assertNull(store.getIfPresent(2L));
    }

    private List<ChatMessage> messages(String text) {
        return List.of(UserMessage.from(text));
    }
}
