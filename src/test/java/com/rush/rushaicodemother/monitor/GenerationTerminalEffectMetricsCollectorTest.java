package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffectBacklog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationTerminalEffectMetricsCollectorTest {

    @Test
    void telemetryMustExposeBoundedOutcomesAndBacklogGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GenerationTerminalEffectMetricsCollector metrics =
                new GenerationTerminalEffectMetricsCollector(registry);
        Instant observedAt = Instant.parse("2026-08-13T08:00:00Z");

        metrics.recordItems("claimed", 3);
        metrics.recordItems("dead_letter", 1);
        metrics.recordBatch("success", Duration.ofMillis(25));
        metrics.recordBacklogRefresh("success");
        metrics.updateBacklog(new GenerationTerminalEffectBacklog(
                8, 3, 2, 1, observedAt.minusSeconds(600)), observedAt);

        assertEquals(3, registry.get("generation_terminal_outbox_items_total")
                .tag("outcome", "claimed").counter().count());
        assertEquals(1, registry.get("generation_terminal_outbox_items_total")
                .tag("outcome", "dead_letter").counter().count());
        assertEquals(1, registry.get("generation_terminal_outbox_batches_total")
                .tag("status", "success").counter().count());
        assertEquals(1, registry.get("generation_terminal_outbox_batch_duration_seconds")
                .tag("status", "success").timer().count());
        assertEquals(1, registry.get("generation_terminal_outbox_backlog_refresh_total")
                .tag("status", "success").counter().count());
        assertEquals(8, gauge(registry, "generation_terminal_outbox_pending"));
        assertEquals(3, gauge(registry, "generation_terminal_outbox_retrying"));
        assertEquals(2, gauge(registry, "generation_terminal_outbox_leased"));
        assertEquals(1, gauge(registry, "generation_terminal_outbox_dead_letter"));
        assertEquals(600, gauge(registry, "generation_terminal_outbox_oldest_age_seconds"));
    }

    private double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }
}
