package com.rush.rushaicodemother.infrastructure.redis;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FailoverChatMemoryStoreTest {

    private static final Long MEMORY_ID = 42L;

    private ChatMemoryStore primaryStore;
    private OptionalRedisOperationMonitor monitor;
    private FailoverChatMemoryStore store;

    @BeforeEach
    void setUp() {
        primaryStore = mock(ChatMemoryStore.class);
        monitor = mock(OptionalRedisOperationMonitor.class);
        store = new FailoverChatMemoryStore(primaryStore, monitor, 100, Duration.ofHours(1));
    }

    @Test
    void shouldReadPrimaryAndRefreshFallbackWhenRedisIsAvailable() {
        List<ChatMessage> messages = messages("from redis");
        when(primaryStore.getMessages(MEMORY_ID)).thenReturn(messages);

        assertEquals(messages, store.getMessages(MEMORY_ID));
        verify(monitor).recordSuccess(OptionalRedisOperation.CHAT_MEMORY_GET);

        RedisConnectionFailureException failure = redisFailure();
        when(primaryStore.getMessages(MEMORY_ID)).thenThrow(failure);

        assertEquals(messages, store.getMessages(MEMORY_ID));
        verify(monitor).recordFailure(OptionalRedisOperation.CHAT_MEMORY_GET, failure);
    }

    @Test
    void shouldReturnEmptyFallbackWhenRedisFailsBeforeAnySuccessfulRead() {
        RedisConnectionFailureException failure = redisFailure();
        when(primaryStore.getMessages(MEMORY_ID)).thenThrow(failure);

        assertEquals(List.of(), store.getMessages(MEMORY_ID));
        verify(monitor).recordFailure(OptionalRedisOperation.CHAT_MEMORY_GET, failure);
    }

    @Test
    void shouldRetainFailedWriteAndFlushItAfterRedisRecovers() {
        List<ChatMessage> messages = messages("written during outage");
        RedisConnectionFailureException failure = redisFailure();
        doThrow(failure).when(primaryStore).updateMessages(MEMORY_ID, messages);

        store.updateMessages(MEMORY_ID, messages);
        verify(monitor).recordFailure(OptionalRedisOperation.CHAT_MEMORY_UPDATE, failure);

        reset(primaryStore, monitor);
        assertEquals(messages, store.getMessages(MEMORY_ID));

        verify(primaryStore).updateMessages(MEMORY_ID, messages);
        verify(primaryStore, never()).getMessages(MEMORY_ID);
        verify(monitor).recordSuccess(OptionalRedisOperation.CHAT_MEMORY_UPDATE);
    }

    @Test
    void shouldRetainFailedDeleteAndFlushTombstoneAfterRedisRecovers() {
        RedisConnectionFailureException failure = redisFailure();
        doThrow(failure).when(primaryStore).deleteMessages(MEMORY_ID);

        store.deleteMessages(MEMORY_ID);

        reset(primaryStore, monitor);
        assertEquals(List.of(), store.getMessages(MEMORY_ID));

        verify(primaryStore).deleteMessages(MEMORY_ID);
        verify(primaryStore, never()).getMessages(MEMORY_ID);
        verify(monitor).recordSuccess(OptionalRedisOperation.CHAT_MEMORY_DELETE);
    }

    @Test
    void shouldKeepPendingWriteWhenRedisIsStillUnavailable() {
        List<ChatMessage> messages = messages("pending");
        RedisConnectionFailureException writeFailure = redisFailure();
        doThrow(writeFailure).when(primaryStore).updateMessages(MEMORY_ID, messages);
        store.updateMessages(MEMORY_ID, messages);

        reset(primaryStore, monitor);
        RedisConnectionFailureException retryFailure = redisFailure();
        doThrow(retryFailure).when(primaryStore).updateMessages(MEMORY_ID, messages);

        assertEquals(messages, store.getMessages(MEMORY_ID));
        verify(monitor).recordFailure(OptionalRedisOperation.CHAT_MEMORY_UPDATE, retryFailure);
    }

    @Test
    void shouldRejectNewPendingMutationWhenCapacityIsFullWithoutLosingExistingWrite() {
        long firstMemoryId = 1L;
        long secondMemoryId = 2L;
        List<ChatMessage> firstMessages = messages("first pending write");
        List<ChatMessage> secondMessages = messages("second pending write");
        FailoverChatMemoryStore boundedStore = new FailoverChatMemoryStore(
                primaryStore,
                monitor,
                1,
                Duration.ofHours(1)
        );
        RedisConnectionFailureException firstFailure = redisFailure();
        RedisConnectionFailureException secondFailure = redisFailure();
        doThrow(firstFailure).when(primaryStore).updateMessages(firstMemoryId, firstMessages);
        doThrow(secondFailure).when(primaryStore).updateMessages(secondMemoryId, secondMessages);

        boundedStore.updateMessages(firstMemoryId, firstMessages);

        assertThrows(
                ChatMemoryFallbackCapacityExceededException.class,
                () -> boundedStore.updateMessages(secondMemoryId, secondMessages)
        );
        verify(monitor).recordFailure(OptionalRedisOperation.CHAT_MEMORY_UPDATE, firstFailure);
        verify(monitor).recordFailure(OptionalRedisOperation.CHAT_MEMORY_UPDATE, secondFailure);

        reset(primaryStore, monitor);
        assertEquals(firstMessages, boundedStore.getMessages(firstMemoryId));
        verify(primaryStore).updateMessages(firstMemoryId, firstMessages);
        verify(primaryStore, never()).getMessages(firstMemoryId);
    }

    @Test
    void shouldRejectInvalidArgumentsWithoutTreatingThemAsRedisFailures() {
        assertThrows(IllegalArgumentException.class, () -> store.getMessages(null));
        assertThrows(IllegalArgumentException.class, () -> store.getMessages(" "));
        assertThrows(IllegalArgumentException.class, () -> store.getMessages("x".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> store.updateMessages(MEMORY_ID, null));
        assertThrows(IllegalArgumentException.class, () -> store.updateMessages(MEMORY_ID, List.of()));

        verifyNoInteractions(primaryStore, monitor);
    }

    @Test
    void shouldPropagateNonRedisProgrammingFailuresWithoutPollutingFallback() {
        IllegalStateException failure = new IllegalStateException("unexpected programming defect");
        when(primaryStore.getMessages(MEMORY_ID)).thenThrow(failure);

        assertEquals(failure, assertThrows(
                IllegalStateException.class,
                () -> store.getMessages(MEMORY_ID)
        ));
        verifyNoInteractions(monitor);
    }

    @Test
    void shouldRejectInvalidFallbackConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FailoverChatMemoryStore(primaryStore, monitor, 0, Duration.ofHours(1))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new FailoverChatMemoryStore(primaryStore, monitor, 1, Duration.ZERO)
        );
    }

    private RedisConnectionFailureException redisFailure() {
        return new RedisConnectionFailureException("connection refused");
    }

    private List<ChatMessage> messages(String text) {
        return List.of(UserMessage.from(text));
    }
}
