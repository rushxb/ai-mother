package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.memory.SemanticMemoryOutboxBacklog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SemanticMemoryMetricsCollectorTest {

    @Test
    void semanticMemoryTelemetryMustUseBoundedTagsAndExposeBacklogGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SemanticMemoryMetricsCollector metrics = new SemanticMemoryMetricsCollector(registry);
        Instant observedAt = Instant.parse("2026-07-21T08:00:00Z");

        metrics.recordStoreOperation("search", "success", Duration.ofMillis(25));
        metrics.recordMalformedRows(2);
        metrics.recordReadiness("failure", Duration.ofMillis(40));
        metrics.recordFailover("search");
        metrics.recordOutboxItems("generation", "claimed", 3);
        metrics.recordOutboxItems("generation", "dead_letter", 1);
        metrics.recordOutboxBatch("generation", "success", Duration.ofMillis(50));
        metrics.recordBacklogRefresh("generation", "success");
        metrics.updateBacklog("generation",
                new SemanticMemoryOutboxBacklog(
                        8, 3, 2, 1, observedAt.minusSeconds(600)), observedAt);

        assertEquals(1, registry.get("semantic_memory_store_operations_total")
                .tags("operation", "search", "status", "success")
                .counter().count());
        assertEquals(1, registry.get("semantic_memory_store_operation_duration_seconds")
                .tags("operation", "search", "status", "success")
                .timer().count());
        assertEquals(2, registry.get("semantic_memory_malformed_rows_total")
                .counter().count());
        assertEquals(1, registry.get("semantic_memory_readiness_checks_total")
                .tag("status", "failure").counter().count());
        assertEquals(1, registry.get("semantic_memory_failovers_total")
                .tag("operation", "search").counter().count());
        assertEquals(3, registry.get("semantic_memory_outbox_items_total")
                .tags("outbox", "generation", "outcome", "claimed")
                .counter().count());
        assertEquals(1, registry.get("semantic_memory_outbox_items_total")
                .tags("outbox", "generation", "outcome", "dead_letter")
                .counter().count());
        assertEquals(8, gauge(registry, "semantic_memory_outbox_pending"));
        assertEquals(3, gauge(registry, "semantic_memory_outbox_retrying"));
        assertEquals(2, gauge(registry, "semantic_memory_outbox_leased"));
        assertEquals(1, gauge(registry, "semantic_memory_outbox_dead_letter"));
        assertEquals(600, gauge(registry, "semantic_memory_outbox_oldest_age_seconds"));
    }

    private double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).tag("outbox", "generation").gauge().value();
    }
}
