package com.rush.rushaicodemother.integration;

import com.rush.rushaicodemother.config.GenerationEventStreamProperties;
import com.rush.rushaicodemother.config.GenerationTaskQueueProperties;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.monitor.GenerationEventStreamMetricsCollector;
import com.rush.rushaicodemother.orchestration.eventstream.RedisGenerationEventStream;
import com.rush.rushaicodemother.orchestration.eventstream.SequencedGenerationEvent;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskLeaseOwnerProvider;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskLeaseProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.queue.GenerationTaskQueueDelivery;
import com.rush.rushaicodemother.orchestration.runtime.task.queue.RedisGenerationTaskQueue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class RedisGenerationRuntimeIntegrationTest {

    private static final String REDIS_HOST = requiredProperty("integration.redis.host");
    private static final int REDIS_PORT = Integer.parseInt(
            requiredProperty("integration.redis.port"));

    @Test
    void eventStreamMustReplayAcrossIndependentClientInstances() {
        LettuceConnectionFactory writerConnection = connectionFactory();
        LettuceConnectionFactory readerConnection = connectionFactory();
        StringRedisTemplate writerTemplate = template(writerConnection);
        StringRedisTemplate readerTemplate = template(readerConnection);
        String suffix = uniqueSuffix();
        String taskId = "event-" + suffix;
        GenerationEventStreamProperties properties = new GenerationEventStreamProperties();
        properties.setKeyPrefix("integration:generation:events:" + suffix + ":");
        properties.setRetention(Duration.ofMinutes(5));
        properties.setPollInterval(Duration.ofMillis(10));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GenerationEventStreamMetricsCollector metricsCollector =
                new GenerationEventStreamMetricsCollector(meterRegistry);
        RedisGenerationEventStream writer = new RedisGenerationEventStream(
                writerTemplate, properties, metricsCollector);
        RedisGenerationEventStream reader = new RedisGenerationEventStream(
                readerTemplate, properties, metricsCollector);
        String streamKey = properties.getKeyPrefix() + taskId;

        try {
            writer.publish(taskId, GenerationStreamEvent.aiDelta("first"));
            writer.publish(taskId, GenerationStreamEvent.aiDelta("second"));
            writer.publish(taskId, GenerationStreamEvent.aiDelta("-third"));
            writer.complete(taskId);

            assertTrue(reader.available(taskId));
            List<SequencedGenerationEvent> replayed = reader.stream(taskId, 0L)
                    .collectList()
                    .block(Duration.ofSeconds(5));
            assertNotNull(replayed);
            assertEquals(List.of(1L, 2L, 3L), replayed.stream()
                    .map(SequencedGenerationEvent::sequence)
                    .toList());
            assertEquals("first", replayed.get(0).event().getText());
            assertEquals("second-third", replayed.get(1).event().getText());
            assertTrue(replayed.get(2).terminal());

            List<SequencedGenerationEvent> resumed = reader.stream(taskId, 1L)
                    .collectList()
                    .block(Duration.ofSeconds(5));
            assertNotNull(resumed);
            assertEquals(List.of(2L, 3L), resumed.stream()
                    .map(SequencedGenerationEvent::sequence)
                    .toList());
        } finally {
            writer.close();
            reader.close();
            meterRegistry.close();
            writerTemplate.delete(List.of(streamKey, "{" + streamKey + "}:sequence"));
            writerConnection.destroy();
            readerConnection.destroy();
        }
    }

    @Test
    void taskQueueMustReclaimAndDeadLetterUnacknowledgedDeliveryAcrossConsumers() throws Exception {
        LettuceConnectionFactory firstConnection = connectionFactory();
        LettuceConnectionFactory secondConnection = connectionFactory();
        StringRedisTemplate firstTemplate = template(firstConnection);
        StringRedisTemplate secondTemplate = template(secondConnection);
        String suffix = uniqueSuffix();
        GenerationTaskQueueProperties properties = queueProperties(suffix);
        RedisGenerationTaskQueue firstQueue = new RedisGenerationTaskQueue(
                firstTemplate, properties, ownerProvider("integration-a"));
        RedisGenerationTaskQueue secondQueue = new RedisGenerationTaskQueue(
                secondTemplate, properties, ownerProvider("integration-b"));
        String taskId = "queue-" + suffix;

        try {
            firstQueue.enqueue(taskId);
            List<GenerationTaskQueueDelivery> deliveries = secondQueue.readNew();

            assertEquals(1, deliveries.size());
            GenerationTaskQueueDelivery initial = deliveries.getFirst();
            assertEquals(taskId, initial.taskId());
            assertEquals(1L, initial.deliveryCount());

            secondQueue.heartbeat(List.of(initial));
            PendingMessages pending = secondTemplate.<String, String>opsForStream().pending(
                    properties.getStreamKey(),
                    properties.getGroup(),
                    Range.unbounded(),
                    10
            );
            assertNotNull(pending);
            assertEquals(1, pending.size());
            assertEquals(1L, pending.get(0).getTotalDeliveryCount());

            GenerationTaskQueueDelivery reclaimed = awaitReclaim(firstQueue);
            assertEquals(initial.messageId(), reclaimed.messageId());
            assertEquals(taskId, reclaimed.taskId());
            assertTrue(reclaimed.deliveryCount() >= 2L);

            firstQueue.deadLetter(reclaimed, "integration_reclaim");
            List<MapRecord<String, String, String>> deadLetters = firstTemplate
                    .<String, String>opsForStream()
                    .range(properties.getDeadLetterStreamKey(), Range.unbounded());
            assertNotNull(deadLetters);
            assertEquals(1, deadLetters.size());
            assertEquals(taskId, deadLetters.getFirst().getValue().get("taskId"));
            assertEquals("integration_reclaim", deadLetters.getFirst().getValue().get("reason"));

            Thread.sleep(properties.getVisibilityTimeout().plusMillis(50).toMillis());
            assertTrue(secondQueue.reclaimExpired().isEmpty());
        } finally {
            firstTemplate.delete(List.of(
                    properties.getStreamKey(),
                    properties.getDeadLetterStreamKey()
            ));
            firstConnection.destroy();
            secondConnection.destroy();
        }
    }

    private GenerationTaskQueueDelivery awaitReclaim(RedisGenerationTaskQueue queue) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<GenerationTaskQueueDelivery> deliveries = queue.reclaimExpired();
            if (!deliveries.isEmpty()) {
                assertEquals(1, deliveries.size());
                return deliveries.getFirst();
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Redis task delivery did not become reclaimable");
    }

    private GenerationTaskQueueProperties queueProperties(String suffix) {
        GenerationTaskQueueProperties properties = new GenerationTaskQueueProperties();
        properties.setStreamKey("integration:generation:tasks:" + suffix);
        properties.setGroup("integration-generation-workers-" + suffix);
        properties.setDeadLetterStreamKey("integration:generation:tasks:dlq:" + suffix);
        properties.setPollTimeout(Duration.ofMillis(100));
        properties.setVisibilityTimeout(Duration.ofMillis(150));
        properties.setDeliveryHeartbeatInterval(Duration.ofMillis(50));
        properties.setReadBatchSize(4);
        properties.setReclaimBatchSize(4);
        return properties;
    }

    private GenerationTaskLeaseOwnerProvider ownerProvider(String ownerId) {
        GenerationTaskLeaseProperties properties = new GenerationTaskLeaseProperties();
        properties.setOwnerId(ownerId);
        return new GenerationTaskLeaseOwnerProvider(properties);
    }

    private LettuceConnectionFactory connectionFactory() {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(REDIS_HOST, REDIS_PORT);
        connectionFactory.afterPropertiesSet();
        return connectionFactory;
    }

    private StringRedisTemplate template(LettuceConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required integration property is missing: " + name);
        }
        return value.trim();
    }
}
