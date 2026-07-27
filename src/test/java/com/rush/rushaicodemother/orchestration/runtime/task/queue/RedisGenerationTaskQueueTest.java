package com.rush.rushaicodemother.orchestration.runtime.task.queue;

import com.rush.rushaicodemother.config.GenerationTaskQueueProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskLeaseOwnerProvider;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskLeaseProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisGenerationTaskQueueTest {

    @Test
    @SuppressWarnings("unchecked")
    void nestedBusyGroupFailureMustBeTreatedAsAnExistingConsumerGroup() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        GenerationTaskQueueProperties properties = new GenerationTaskQueueProperties();
        when(redisTemplate.hasKey(properties.getStreamKey())).thenReturn(true);
        when(redisTemplate.<String, String>opsForStream()).thenReturn(operations);
        when(operations.createGroup(
                eq(properties.getStreamKey()),
                any(ReadOffset.class),
                eq(properties.getGroup())
        )).thenThrow(new RedisSystemException(
                "Error in execution",
                new IllegalStateException("BUSYGROUP Consumer Group name already exists")
        ));
        when(operations.read(
                any(org.springframework.data.redis.connection.stream.Consumer.class),
                any(StreamReadOptions.class),
                any(StreamOffset[].class)
        )).thenReturn(List.of());
        RedisGenerationTaskQueue queue = queue(redisTemplate, properties);

        assertDoesNotThrow(queue::readNew);
    }

    @Test
    void unrelatedConsumerGroupFailureMustStillFailClosed() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        GenerationTaskQueueProperties properties = new GenerationTaskQueueProperties();
        when(redisTemplate.hasKey(properties.getStreamKey())).thenReturn(true);
        when(redisTemplate.<String, String>opsForStream()).thenReturn(operations);
        when(operations.createGroup(
                eq(properties.getStreamKey()),
                any(ReadOffset.class),
                eq(properties.getGroup())
        )).thenThrow(new RedisSystemException(
                "Error in execution",
                new IllegalStateException("NOAUTH Authentication required")
        ));
        RedisGenerationTaskQueue queue = queue(redisTemplate, properties);

        assertThrows(RedisSystemException.class, queue::readNew);
    }

    @Test
    void heartbeatMustRenewVisibilityWithoutIncreasingDeliveryCount() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisConnection connection = mock(RedisConnection.class);
        RedisStreamCommands streamCommands = mock(RedisStreamCommands.class);
        GenerationTaskQueueProperties properties = new GenerationTaskQueueProperties();
        when(connection.streamCommands()).thenReturn(streamCommands);
        when(redisTemplate.execute(anyRedisCallback())).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });
        RedisGenerationTaskQueue queue = queue(redisTemplate, properties);
        GenerationTaskQueueDelivery delivery = new GenerationTaskQueueDelivery(
                "1784778000000-0", "task-1", 1L);

        queue.heartbeat(List.of(delivery));

        ArgumentCaptor<byte[]> keyCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<RedisStreamCommands.XClaimOptions> optionsCaptor =
                ArgumentCaptor.forClass(RedisStreamCommands.XClaimOptions.class);
        verify(streamCommands).xClaimJustId(
                keyCaptor.capture(),
                eq(properties.getGroup()),
                anyString(),
                optionsCaptor.capture()
        );
        assertArrayEquals(
                StringRedisSerializer.UTF_8.serialize(properties.getStreamKey()),
                keyCaptor.getValue()
        );
        assertEquals(Duration.ZERO, optionsCaptor.getValue().getMinIdleTime());
        assertEquals(List.of(RecordId.of(delivery.messageId())), optionsCaptor.getValue().getIds());
    }

    @SuppressWarnings("unchecked")
    private RedisCallback<List<RecordId>> anyRedisCallback() {
        return any(RedisCallback.class);
    }

    private RedisGenerationTaskQueue queue(
            StringRedisTemplate redisTemplate,
            GenerationTaskQueueProperties properties
    ) {
        return new RedisGenerationTaskQueue(
                redisTemplate,
                properties,
                new GenerationTaskLeaseOwnerProvider(new GenerationTaskLeaseProperties())
        );
    }
}
