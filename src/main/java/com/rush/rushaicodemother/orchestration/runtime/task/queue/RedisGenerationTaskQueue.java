package com.rush.rushaicodemother.orchestration.runtime.task.queue;

import com.rush.rushaicodemother.config.GenerationTaskQueueProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskLeaseOwnerProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 支持续期、回收和死信的 Redis Streams 消费组适配器。 */
@Component
@ConditionalOnProperty(prefix = "app.generation-task-queue", name = "transport", havingValue = "redis")
public class RedisGenerationTaskQueue implements DurableGenerationTaskQueue {

    private static final String FIELD_KIND = "kind";
    private static final String FIELD_TASK_ID = "taskId";
    private static final String FIELD_ENQUEUED_AT = "enqueuedAt";
    private static final String KIND_TASK = "generation_task";
    private static final String KIND_BOOTSTRAP = "bootstrap";

    private final StringRedisTemplate redisTemplate;
    private final GenerationTaskQueueProperties properties;
    private final String consumerName;
    private final AtomicBoolean groupReady = new AtomicBoolean(false);

    public RedisGenerationTaskQueue(StringRedisTemplate redisTemplate,
                                    GenerationTaskQueueProperties properties,
                                    GenerationTaskLeaseOwnerProvider ownerProvider) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.consumerName = normalizeConsumerName(ownerProvider.ownerId());
    }

    /**
 * 处理{@code enqueue}。
 *
 * @param taskId 任务编号
 */
    @Override
    public void enqueue(String taskId) {
        requireTaskId(taskId);
        ensureGroup();
        redisTemplate.<String, String>opsForStream().add(
                StreamRecords.newRecord().in(properties.getStreamKey()).ofStrings(Map.of(
                        FIELD_KIND, KIND_TASK,
                        FIELD_TASK_ID, taskId,
                        FIELD_ENQUEUED_AT, Instant.now().toString()
                ))
        );
        redisTemplate.<String, String>opsForStream()
                .trim(properties.getStreamKey(), properties.getMaxStreamLength(), true);
    }

    /**
 * 读取{@code New}。
 *
 * @return {@code New}集合
 */
    @Override
    @SuppressWarnings("unchecked")
    public List<GenerationTaskQueueDelivery> readNew() {
        ensureGroup();
        List<MapRecord<String, String, String>> records = redisTemplate
                .<String, String>opsForStream().read(
                        Consumer.from(properties.getGroup(), consumerName),
                        StreamReadOptions.empty()
                                .count(properties.getReadBatchSize())
                                .block(properties.getPollTimeout()),
                        StreamOffset.create(properties.getStreamKey(), ReadOffset.lastConsumed())
                );
        return toDeliveries(records, Map.of());
    }

    /**
 * 返回{@code reclaim}{@code Expired}。
 *
 * @return Redis 生成任务{@code Queue}集合
 */
    @Override
    public List<GenerationTaskQueueDelivery> reclaimExpired() {
        ensureGroup();
        var pending = redisTemplate.<String, String>opsForStream().pending(
                properties.getStreamKey(),
                properties.getGroup(),
                Range.unbounded(),
                properties.getReclaimBatchSize()
        );
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        List<RecordId> ids = new ArrayList<>();
        Map<String, Long> deliveryCounts = new HashMap<>();
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(properties.getVisibilityTimeout()) < 0) {
                continue;
            }
            ids.add(message.getId());
            deliveryCounts.put(message.getIdAsString(), message.getTotalDeliveryCount() + 1);
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<MapRecord<String, String, String>> claimed = redisTemplate
                .<String, String>opsForStream().claim(
                        properties.getStreamKey(),
                        properties.getGroup(),
                        consumerName,
                        properties.getVisibilityTimeout(),
                        ids.toArray(RecordId[]::new)
                );
        return toDeliveries(claimed, deliveryCounts);
    }

    /**
 * 处理心跳。
 *
 * @param deliveries 待处理的 {@code deliveries} 集合
 */
    @Override
    public void heartbeat(Collection<GenerationTaskQueueDelivery> deliveries) {
        if (deliveries == null || deliveries.isEmpty()) {
            return;
        }
        RecordId[] ids = deliveries.stream()
                .filter(Objects::nonNull)
                .map(GenerationTaskQueueDelivery::messageId)
                .map(RecordId::of)
                .toArray(RecordId[]::new);
        if (ids.length == 0) {
            return;
        }
        byte[] streamKey = Objects.requireNonNull(
                StringRedisSerializer.UTF_8.serialize(properties.getStreamKey()),
                "Redis Stream 键序列化结果不能为空"
        );
        RedisStreamCommands.XClaimOptions options = RedisStreamCommands.XClaimOptions
                .minIdle(Duration.ZERO)
                .ids(ids);
        redisTemplate.execute((RedisCallback<List<RecordId>>) connection ->
                connection.streamCommands().xClaimJustId(
                        streamKey,
                        properties.getGroup(),
                        consumerName,
                        options
                ));
    }

    /**
 * 处理{@code acknowledge}。
 *
 * @param delivery {@code delivery} 对应的调用参数
 */
    @Override
    public void acknowledge(GenerationTaskQueueDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        redisTemplate.<String, String>opsForStream().acknowledge(
                properties.getStreamKey(), properties.getGroup(), RecordId.of(delivery.messageId()));
    }

    /**
 * 处理{@code dead}{@code Letter}。
 *
 * @param delivery {@code delivery} 对应的调用参数
 * @param reason 原因
 */
    @Override
    public void deadLetter(GenerationTaskQueueDelivery delivery, String reason) {
        Objects.requireNonNull(delivery, "delivery");
        String normalizedReason = reason == null || reason.isBlank() ? "delivery_exhausted" : reason.trim();
        redisTemplate.<String, String>opsForStream().add(
                StreamRecords.newRecord().in(properties.getDeadLetterStreamKey()).ofStrings(Map.of(
                        FIELD_KIND, KIND_TASK,
                        FIELD_TASK_ID, delivery.taskId(),
                        "sourceMessageId", delivery.messageId(),
                        "deliveryCount", Long.toString(delivery.deliveryCount()),
                        "reason", normalizedReason,
                        "deadLetteredAt", Instant.now().toString()
                ))
        );
        acknowledge(delivery);
    }

    /** 将当前对象转换为{@code Deliveries}。 */
    private List<GenerationTaskQueueDelivery> toDeliveries(
            List<MapRecord<String, String, String>> records,
            Map<String, Long> deliveryCounts) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<GenerationTaskQueueDelivery> deliveries = new ArrayList<>();
        for (MapRecord<String, String, String> record : records) {
            Map<String, String> values = record.getValue();
            if (!KIND_TASK.equals(values.get(FIELD_KIND))) {
                acknowledgeRaw(record.getId());
                continue;
            }
            String taskId = values.get(FIELD_TASK_ID);
            if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
                acknowledgeRaw(record.getId());
                continue;
            }
            deliveries.add(new GenerationTaskQueueDelivery(
                    record.getId().getValue(),
                    taskId,
                    deliveryCounts.getOrDefault(record.getId().getValue(), 1L)
            ));
        }
        return List.copyOf(deliveries);
    }

    private void acknowledgeRaw(RecordId id) {
        redisTemplate.<String, String>opsForStream().acknowledge(
                properties.getStreamKey(), properties.getGroup(), id);
    }

    /** 确保分组已达到可用状态。 */
    private void ensureGroup() {
        if (groupReady.get()) {
            return;
        }
        synchronized (groupReady) {
            if (groupReady.get()) {
                return;
            }
            try {
                if (!Boolean.TRUE.equals(redisTemplate.hasKey(properties.getStreamKey()))) {
                    redisTemplate.<String, String>opsForStream().add(
                            StreamRecords.newRecord().in(properties.getStreamKey()).ofStrings(Map.of(
                                    FIELD_KIND, KIND_BOOTSTRAP,
                                    FIELD_ENQUEUED_AT, Instant.now().toString()
                            ))
                    );
                }
                redisTemplate.<String, String>opsForStream().createGroup(
                        properties.getStreamKey(), ReadOffset.latest(), properties.getGroup());
            } catch (DataAccessException existingGroup) {
                if (!isExistingConsumerGroup(existingGroup)) {
                    throw existingGroup;
                }
            }
            groupReady.set(true);
        }
    }

    /** 判断{@code Existing}消费者分组是否满足约束。 */
    private boolean isExistingConsumerGroup(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            String message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    private void requireTaskId(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("taskId format is invalid");
        }
    }

    private String normalizeConsumerName(String value) {
        String normalized = value == null ? "worker" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }
}
