package com.rush.rushaicodemother.orchestration.eventstream;

import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.config.GenerationEventStreamProperties;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisGenerationEventStreamTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void appendMustUseAtomicScriptAndStoreOnlySanitizedPublicPayload() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        GenerationEventStreamProperties properties = properties();
        RedisGenerationEventStream stream = new RedisGenerationEventStream(redisTemplate, properties);
        String secret = "redis-secret";

        stream.publish("task-redis", GenerationStreamEvent.toolCall("raw", Map.of(
                "toolName", "writeFile",
                "filePath", "src/App.vue",
                "arguments", "{\"password\":\"" + secret + "\"}",
                "content", "password=" + secret
        )));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(
                        "generation:events:task-redis",
                        "{generation:events:task-redis}:sequence"
                )),
                arguments.capture()
        );
        Object[] scriptArguments = arguments.getValue();
        assertEquals("event", scriptArguments[0]);
        assertFalse(String.valueOf(scriptArguments[1]).contains(secret));
        assertFalse(String.valueOf(scriptArguments[1]).contains("arguments"));
        assertEquals(Integer.toString(properties.getMaxEventsPerTask()), scriptArguments[2]);
        assertEquals(Long.toString(properties.getRetention().toMillis()), scriptArguments[3]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamMustReplaySequencedRecordsEmitGapAndStopAtCompletion() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        when(redisTemplate.<String, String>opsForStream()).thenReturn(operations);
        String streamKey = "generation:events:task-redis";
        MapRecord<String, String, String> event = MapRecord.create(streamKey, Map.of(
                "sequence", "5",
                "kind", "event",
                "payload", JSONUtil.toJsonStr(GenerationStreamEvent.aiDelta("resumed"))
        )).withId(RecordId.of("1-0"));
        MapRecord<String, String, String> complete = MapRecord.create(streamKey, Map.of(
                "sequence", "6",
                "kind", "complete",
                "payload", ""
        )).withId(RecordId.of("2-0"));
        when(operations.read(
                any(StreamReadOptions.class),
                any(StreamOffset[].class)
        )).thenReturn(List.of(event, complete));
        RedisGenerationEventStream stream = new RedisGenerationEventStream(redisTemplate, properties());

        List<SequencedGenerationEvent> replayed = stream.stream("task-redis", 2L)
                .collectList()
                .block(Duration.ofSeconds(2));

        assertEquals(List.of(4L, 5L, 6L), replayed.stream()
                .map(SequencedGenerationEvent::sequence)
                .toList());
        assertEquals(SequencedGenerationEvent.Kind.GAP, replayed.getFirst().kind());
        assertEquals(2L, replayed.getFirst().gap().requestedSeq());
        assertEquals(5L, replayed.getFirst().gap().firstAvailableSeq());
        assertEquals("resumed", replayed.get(1).event().getText());
        assertTrue(replayed.getLast().terminal());
    }

    private GenerationEventStreamProperties properties() {
        GenerationEventStreamProperties properties = new GenerationEventStreamProperties();
        properties.setPollInterval(Duration.ofMillis(10));
        return properties;
    }
}
