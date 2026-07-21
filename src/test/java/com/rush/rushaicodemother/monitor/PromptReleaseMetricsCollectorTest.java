package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptReleaseMetricsCollectorTest {

    @Test
    void shouldRecordLowCardinalityRefreshMutationAndRevisionMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PromptReleaseMetricsCollector collector = new PromptReleaseMetricsCollector(registry);

        collector.recordRefresh("activated", Duration.ofMillis(25), 8L);
        collector.recordRefresh("tenant-controlled-status", Duration.ZERO, 8L);
        collector.recordMutation("rollback", "success");

        assertEquals(1, registry.find("ai_prompt_release_refresh_total")
                .tag("status", "activated")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("ai_prompt_release_refresh_total")
                .tag("status", "failed")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("ai_prompt_release_refresh_duration_seconds")
                .tag("status", "activated")
                .timer()
                .count());
        assertEquals(1, registry.find("ai_prompt_release_mutations_total")
                .tag("action", "rollback")
                .tag("status", "success")
                .counter()
                .count(), 0.001);
        assertEquals(8, registry.find("ai_prompt_release_active_revision").gauge().value(), 0.001);
    }
}
