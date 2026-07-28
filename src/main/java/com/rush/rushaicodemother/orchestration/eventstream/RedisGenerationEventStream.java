package com.rush.rushaicodemother.orchestration.eventstream;

import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.config.GenerationEventStreamProperties;
import com.rush.rushaicodemother.core.handler.GenerationPublicEventSanitizer;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.monitor.GenerationEventStreamMetricsCollector;
import jakarta.annotation.PreDestroy;
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

/** 生产节点使用的 Redis Streams 适配器，提供可重放的跨实例 SSE 事件。 */
@Component
@ConditionalOnProperty(prefix = "app.generation-event-stream", name = "transport", havingValue = "redis")
public class RedisGenerationEventStream implements GenerationEventStream, AutoCloseable {

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
    private final GenerationEventStreamMetricsCollector metricsCollector;
    private final GenerationEventDeltaCoalescer deltaCoalescer;

    /**
 * 创建 Redis 生成事件流实例并完成必要的依赖和初始状态设置。
 *
 * @param redisTemplate Redis 操作模板
 * @param properties 配置属性
 * @param metricsCollector {@code metricsCollector} 对应的调用参数
 */
    public RedisGenerationEventStream(StringRedisTemplate redisTemplate,
                                      GenerationEventStreamProperties properties,
                                      GenerationEventStreamMetricsCollector metricsCollector) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metricsCollector = metricsCollector;
        this.deltaCoalescer = new GenerationEventDeltaCoalescer(
                properties,
                new GenerationEventDeltaCoalescer.EventWriter() {
                    @Override
                    public void publish(String taskId, GenerationStreamEvent event) {
                        appendEvent(taskId, event);
                    }

                    @Override
                    public void complete(String taskId) {
                        append(taskId, KIND_COMPLETE, "");
                    }
                },
                metricsCollector
        );
    }

    /**
 * 发布当前处理结果或领域事件。
 *
 * @param taskId 任务编号
 * @param event 待处理的领域事件
 */
    @Override
    public void publish(String taskId, GenerationStreamEvent event) {
        if (!validTaskId(taskId) || event == null) {
            return;
        }
        GenerationStreamEvent publicEvent = GenerationPublicEventSanitizer.sanitize(event);
        if (publicEvent == null) {
            return;
        }
        deltaCoalescer.publish(taskId, publicEvent);
    }

    /**
 * 完成 Redis 生成事件流并持久化终态。
 *
 * @param taskId 任务编号
 */
    @Override
    public void complete(String taskId) {
        if (!validTaskId(taskId)) {
            return;
        }
        deltaCoalescer.complete(taskId);
    }

    /**
 * 返回可用。
 *
 * @param taskId 任务编号
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean available(String taskId) {
        return validTaskId(taskId) && Boolean.TRUE.equals(redisTemplate.hasKey(key(taskId)));
    }

    /**
 * 返回流。
 *
 * @param taskId 任务编号
 * @param afterSequence 执行后序列
 * @return 异步响应式处理结果
 */
    @Override
    public Flux<SequencedGenerationEvent> stream(String taskId, long afterSequence) {
        if (!validTaskId(taskId)) {
            return Flux.empty();
        }
        if (afterSequence < 0) {
            throw new IllegalArgumentException("生成事件游标不能为负数");
        }
        String streamKey = key(taskId);
        return Flux.defer(() -> resumableStream(streamKey, afterSequence));
    }

    /** 返回{@code resumable}流。 */
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

    /** 追加 Redis 生成事件流。 */
    private void append(String taskId, String kind, String payload) {
        long startedAt = System.nanoTime();
        boolean success = false;
        try {
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
                throw new IllegalStateException("Redis 生成事件追加返回了无效序号");
            }
            success = true;
        } finally {
            metricsCollector.recordRedisAppend(
                    kind,
                    success ? "success" : "failed",
                    Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAt))
            );
        }
    }

    private void appendEvent(String taskId, GenerationStreamEvent event) {
        append(taskId, KIND_EVENT, JSONUtil.toJsonStr(event));
    }

    @SuppressWarnings("unchecked") // Spring Data 通过泛型可变参数暴露 StreamOffset<K>。
    private List<MapRecord<String, String, String>> read(String streamKey, String offset) {
        List<MapRecord<String, String, String>> records = redisTemplate
                .<String, String>opsForStream().read(
                StreamReadOptions.empty().count(properties.getReadBatchSize()),
                StreamOffset.create(streamKey, ReadOffset.from(offset))
        );
        return records == null ? List.of() : records;
    }

    /** 将当前对象转换为{@code Sequenced}事件。 */
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
            throw new IllegalStateException("Redis 生成事件记录包含不支持的类型");
        }
        String payload = values.get(FIELD_PAYLOAD);
        GenerationStreamEvent event = GenerationPublicEventSanitizer.sanitize(
                JSONUtil.toBean(payload, GenerationStreamEvent.class));
        if (event == null) {
            throw new IllegalStateException("Redis 生成事件记录缺少公开载荷");
        }
        return SequencedGenerationEvent.event(sequence, event);
    }

    /** 解析序列。 */
    private long parseSequence(String value, AtomicLong legacySequence) {
        if (value == null || value.isBlank()) {
            return legacySequence.incrementAndGet();
        }
        try {
            long sequence = Long.parseLong(value);
            if (sequence <= 0) {
                throw new IllegalArgumentException("事件序号必须为正数");
            }
            legacySequence.accumulateAndGet(sequence, Math::max);
            return sequence;
        } catch (RuntimeException invalidSequence) {
            throw new IllegalStateException("Redis 生成事件记录包含无效序号", invalidSequence);
        }
    }

    private String key(String taskId) {
        return properties.getKeyPrefix() + taskId;
    }

    /** 使用与原始流键一致的哈希标签，确保两个 Lua 键位于同一 Redis Cluster 槽。 */
    private String sequenceKey(String streamKey) {
        return "{" + streamKey + "}:sequence";
    }

    private boolean validTaskId(String taskId) {
        return taskId != null && taskId.matches("[A-Za-z0-9_-]{1,128}");
    }

    @Override
    @PreDestroy
    public void close() {
        deltaCoalescer.close();
    }
}
