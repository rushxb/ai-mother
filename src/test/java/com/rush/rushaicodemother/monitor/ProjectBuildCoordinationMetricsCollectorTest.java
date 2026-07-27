package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectBuildCoordinationMetricsCollectorTest {

    @Test
    void shouldRecordBoundedBuildCoordinationMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProjectBuildCoordinationMetricsCollector collector =
                new ProjectBuildCoordinationMetricsCollector(registry);

        collector.recordEvent("vue", "task_reused");
        collector.recordEvent("tenant-project-type", "tenant-event");
        collector.recordJoinWait("vue", "success", Duration.ofMillis(250));

        assertEquals(1, registry.find("generation_project_build_coordination_total")
                .tag("project_type", "vue")
                .tag("event", "task_reused")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("generation_project_build_coordination_total")
                .tag("project_type", "unknown")
                .tag("event", "other")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("generation_project_build_join_wait_duration_seconds")
                .tag("project_type", "vue")
                .tag("status", "success")
                .timer()
                .count());
    }
}
