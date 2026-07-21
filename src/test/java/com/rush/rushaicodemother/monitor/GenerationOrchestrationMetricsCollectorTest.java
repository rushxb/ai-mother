package com.rush.rushaicodemother.monitor;

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
        collector.recordGenerationCommit("local_git", "committed", "");
        collector.recordAutoRepair("heavy", "build", "started");
        collector.recordRuntimeValidation("heavy", "vue_project", "PASS");
        collector.recordUserWaitDuration("heavy", "vue_project", "success", Duration.ofSeconds(3));
        collector.recordFirstPreviewDuration("create", "vue_project", "met", Duration.ofSeconds(20));
        collector.recordSlaOutcome("create", "first_preview", "met", "within_deadline");
        collector.recordContextSnapshot("heavy", "intent_selected_files", 2, 8, 12, 2, 1200);

        assertEquals(1, meterRegistry.find("generation_orchestration_patch_apply_total")
                .tag("provider", "local_patch_executor")
                .tag("status", "applied")
                .tag("reason", "unknown")
                .counter()
                .count(), 0.001);
        assertEquals(1, meterRegistry.find("generation_orchestration_commit_total")
                .tag("provider", "local_git")
                .tag("status", "committed")
                .tag("reason", "unknown")
                .counter()
                .count(), 0.001);
        assertEquals(1, meterRegistry.find("generation_orchestration_auto_repair_total")
                .tag("orchestration_mode", "heavy")
                .tag("stage", "build")
                .tag("status", "started")
                .counter()
                .count(), 0.001);
        assertEquals(1, meterRegistry.find("generation_orchestration_runtime_validation_total")
                .tag("orchestration_mode", "heavy")
                .tag("target_type", "vue_project")
                .tag("status", "pass")
                .counter()
                .count(), 0.001);
        assertEquals(1, meterRegistry.find("generation_orchestration_user_wait_duration_seconds")
                .tag("orchestration_mode", "heavy")
                .tag("target_type", "vue_project")
                .tag("status", "success")
                .timer()
                .count());
        assertEquals(1, meterRegistry.find("generation_time_to_first_preview_seconds")
                .tag("orchestration_mode", "create")
                .tag("target_type", "vue_project")
                .tag("sla_status", "met")
                .timer()
                .count());
        assertEquals(1, meterRegistry.find("generation_sla_outcomes_total")
                .tag("orchestration_mode", "create")
                .tag("milestone", "first_preview")
                .tag("status", "met")
                .tag("reason", "within_deadline")
                .counter()
                .count(), 0.001);
        assertEquals(1, meterRegistry.find("generation_orchestration_indexed_symbols")
                .tag("orchestration_mode", "heavy")
                .tag("context_mode", "intent_selected_files")
                .summary()
                .count());
        assertEquals(2, meterRegistry.find("generation_orchestration_index_hits")
                .tag("orchestration_mode", "heavy")
                .tag("context_mode", "intent_selected_files")
                .summary()
                .totalAmount(), 0.001);
    }
}
