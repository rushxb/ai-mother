package com.yupi.yuaicodemother.monitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationOrchestrationMetricsCollectorTest {

    @Test
    void shouldRecordExecutionClosedLoopMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GenerationOrchestrationMetricsCollector collector =
                new GenerationOrchestrationMetricsCollector(meterRegistry);

        collector.recordPatchApply("local_patch_executor", "applied", "");
        collector.recordAutoRepair("heavy", "build", "started");
        collector.recordUserWaitDuration("heavy", "vue_project", "success", Duration.ofSeconds(3));

        assertEquals(1, meterRegistry.find("generation_orchestration_patch_apply_total")
                .tag("provider", "local_patch_executor")
                .tag("status", "applied")
                .tag("reason", "unknown")
                .counter()
                .count(), 0.001);
        assertEquals(1, meterRegistry.find("generation_orchestration_auto_repair_total")
                .tag("orchestration_mode", "heavy")
                .tag("stage", "build")
                .tag("status", "started")
                .counter()
                .count(), 0.001);
        assertEquals(1, meterRegistry.find("generation_orchestration_user_wait_duration_seconds")
                .tag("orchestration_mode", "heavy")
                .tag("target_type", "vue_project")
                .tag("status", "success")
                .timer()
                .count());
    }
}
