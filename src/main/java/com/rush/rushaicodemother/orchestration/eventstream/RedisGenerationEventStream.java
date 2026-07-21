package com.rush.rushaicodemother.orchestration.eventstream;

import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.config.GenerationEventStreamProperties;
import com.rush.rushaicodemother.core.handler.GenerationPublicEventSanitizer;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Redis Streams adapter used by production nodes for replayable cross-instance SSE delivery. */
@Component
@ConditionalOnProperty(prefix = "app.generation-event-stream", name = "transport", havingValue = "redis")
public class RedisGenerationEventStream implements GenerationEventStream {

    private static final String FIELD_SEQUENCE = "sequence";
    private static final String FIELD_KIND = "kind";
    private static final String FIELD_PAYLOAD = "payload";
    private static final String KIND_EVENT = "event";
    private static final String KIND_COMPLETE = "complete";
    private static final DefaultRedisScript<Long> APPEND_SCRIPT = new DefaultRedisScript<>("""
            local seed = redis.call('GET', KEYS[2])
            if not seed then
                seed = redis.call('XLEN', KEYS[1])
                local tail = redis.call('XREVRANGE', KEYS[1], '+', '-', 'COUNT', 1)
                if #tail > 0 then
                    local fields = tail[1][2]
                    for index = 1, #fields, 2 do
                        if fields[index] == 'sequence' then
                            seed = fields[index + 1]
                            break
                        end
                    end
                end
                redis.call('SET', KEYS[2], seed)
            end
            local sequence = redis.call('INCR', KEYS[2])
            redis.call('XADD', KEYS[1], '*',
                    'sequence', tostring(sequence),
                    'kind', ARGV[1],
                    'payload', ARGV[2])
            redis.call('XTRIM', KEYS[1], 'MAXLEN', '~', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            redis.call('PEXPIRE', KEYS[2], ARGV[4])
            return sequence
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final GenerationEventStreamProperties properties;

    public RedisGenerationEventStream(StringRedisTemplate redisTemplate,
                                      GenerationEventStreamProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(String taskId, GenerationStreamEvent event) {
        if (!validTaskId(taskId) || event == null) {
            return;
        }
        GenerationStreamEvent publicEvent = GenerationPublicEventSanitizer.sanitize(event);
        if (publicEvent == null) {
            return;
        }
        append(taskId, KIND_EVENT, JSONUtil.toJsonStr(publicEvent));
    }

    @Override
    public void complete(String taskId) {
        if (!validTaskId(taskId)) {
            return;
        }
        append(taskId, KIND_COMPLETE, "");
    }

    @Override
    public boolean available(String taskId) {
        return validTaskId(taskId) && Boolean.TRUE.equals(redisTemplate.hasKey(key(taskId)));
    }

    @Override
    public Flux<SequencedGenerationEvent> stream(String taskId, long afterSequence) {
        if (!validTaskId(taskId)) {
            return Flux.empty();
        }
        if (afterSequence < 0) {
            throw new IllegalArgumentException("generation event cursor cannot be negative");
        }
        String streamKey = key(taskId);
        return Flux.defer(() -> resumableStream(streamKey, afterSequence));
    }

    private Flux<SequencedGenerationEvent> resumableStream(String streamKey, long afterSequence) {
        AtomicReference<String> offset = new AtomicReference<>("0-0");
        AtomicLong legacySequence = new AtomicLong(0L);
        AtomicLong replayCursor = new AtomicLong(afterSequence);
        Flux<List<MapRecord<String, String, String>>> batches = Flux.defer(() ->
                        Mono.fromCallable(() -> read(streamKey, offset.get()))
                                .subscribeOn(Schedulers.boundedElastic()))
                .repeatWhen(repeats -> repeats.delayElements(properties.getPollInterval()));
        Flux<SequencedGenerationEvent> records = batches
                .flatMapIterable(batch -> batch)
                .map(record -> toSequencedEvent(record, offset, legacySequence));
        return GenerationEventReplayCursor.resume(records, replayCursor)
                .takeUntil(SequencedGenerationEvent::terminal)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(250))
                        .maxBackoff(Duration.ofSeconds(5))
                        .jitter(0.25));
    }

    private void append(String taskId, String kind, String payload) {
        String streamKey = key(taskId);
        Long sequence = redisTemplate.execute(
                APPEND_SCRIPT,
                List.of(streamKey, sequenceKey(streamKey)),
                kind,
                payload,
                Integer.toString(properties.getMaxEventsPerTask()),
                Long.toString(properties.getRetention().toMillis())
        );
        if (sequence == null || sequence <= 0) {
            throw new IllegalStateException("Redis generation event append returned an invalid sequence");
        }
    }

    @SuppressWarnings("unchecked") // Spring Data exposes StreamOffset<K> through a generic varargs API.
    private List<MapRecord<String, String, String>> read(String streamKey, String offset) {
        List<MapRecord<String, String, String>> records = redisTemplate
                .<String, String>opsForStream().read(
                StreamReadOptions.empty().count(properties.getReadBatchSize()),
                StreamOffset.create(streamKey, ReadOffset.from(offset))
        );
        return records == null ? List.of() : records;
    }

    private SequencedGenerationEvent toSequencedEvent(
            MapRecord<String, String, String> record,
            AtomicReference<String> offset,
            AtomicLong legacySequence
    ) {
        offset.set(record.getId().getValue());
        Map<String, String> values = record.getValue();
        long sequence = parseSequence(values.get(FIELD_SEQUENCE), legacySequence);
        String kind = values.get(FIELD_KIND);
        if (KIND_COMPLETE.equals(kind)) {
            return SequencedGenerationEvent.complete(sequence);
        }
        if (!KIND_EVENT.equals(kind)) {
            throw new IllegalStateException("Redis generation event record has an unsupported kind");
        }
        String payload = values.get(FIELD_PAYLOAD);
        GenerationStreamEvent event = GenerationPublicEventSanitizer.sanitize(
                JSONUtil.toBean(payload, GenerationStreamEvent.class));
        if (event == null) {
            throw new IllegalStateException("Redis generation event record has no public payload");
        }
        return SequencedGenerationEvent.event(sequence, event);
    }

    private long parseSequence(String value, AtomicLong legacySequence) {
        if (value == null || value.isBlank()) {
            return legacySequence.incrementAndGet();
        }
        try {
            long sequence = Long.parseLong(value);
            if (sequence <= 0) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            legacySequence.accumulateAndGet(sequence, Math::max);
            return sequence;
        } catch (RuntimeException invalidSequence) {
            throw new IllegalStateException("Redis generation event record has an invalid sequence", invalidSequence);
        }
    }

    private String key(String taskId) {
        return properties.getKeyPrefix() + taskId;
    }

    /** Uses a hash tag whose value equals the untagged stream key, keeping both Lua keys in one cluster slot. */
    private String sequenceKey(String streamKey) {
        return "{" + streamKey + "}:sequence";
    }

    private boolean validTaskId(String taskId) {
        return taskId != null && taskId.matches("[A-Za-z0-9_-]{1,128}");
    }
}