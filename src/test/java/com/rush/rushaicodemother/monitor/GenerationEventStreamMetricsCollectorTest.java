package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationEventStreamMetricsCollectorTest {

    @Test
    void shouldRecordAppendAndCoalescingMetricsWithBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GenerationEventStreamMetricsCollector collector = new GenerationEventStreamMetricsCollector(registry);

        collector.recordRedisAppend("event", "success", Duration.ofMillis(12));
        collector.recordRedisAppend("tenant-controlled-kind", "unexpected", Duration.ofMillis(-1));
        collector.recordDeltaInput("buffered");
        collector.recordDeltaInput("tenant-controlled-disposition");
        collector.recordDeltaFlush("window", "success", 3, 18);

        assertEquals(1, registry.find("generation_event_stream_redis_appends_total")
                .tag("kind", "event")
                .tag("outcome", "success")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("generation_event_stream_redis_appends_total")
                .tag("kind", "unknown")
                .tag("outcome", "failed")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("generation_event_stream_redis_append_duration_seconds")
                .tag("kind", "event")
                .tag("outcome", "success")
                .timer()
                .count());
        assertEquals(1, registry.find("generation_event_stream_delta_inputs_total")
                .tag("disposition", "buffered")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("generation_event_stream_delta_inputs_total")
                .tag("disposition", "unknown")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("generation_event_stream_delta_flushes_total")
                .tag("trigger", "window")
                .tag("outcome", "success")
                .counter()
                .count(), 0.001);
        assertEquals(3, registry.find("generation_event_stream_delta_events_per_flush")
                .tag("trigger", "window")
                .tag("outcome", "success")
                .summary()
                .totalAmount(), 0.001);
        assertEquals(18, registry.find("generation_event_stream_delta_chars_per_flush")
                .tag("trigger", "window")
                .tag("outcome", "success")
                .summary()
                .totalAmount(), 0.001);
    }
}
