package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationPerformanceMonitorServiceTest {

    @Test
    void shouldExposePhaseFourRuntimeTelemetry() {
        GenerationPerformanceMonitorService service = new GenerationPerformanceMonitorService();
        service.startTask("task-1", 1L, 2L, "agent_edit", "vue_project", java.time.Instant.now(),
                new GenerationModeDecision(
                        GenerationMode.AGENT_EDIT,
                        0.9,
                        "test",
                        FallbackPolicy.NONE,
                        ExpectedValidationLevel.BUILD,
                        ""
                ));

        service.recordRuntimeTelemetry("task-1", Map.of(
                "modelName", "routing_chat_model",
                "firstTokenLatencyMs", 12,
                "totalAiDurationMs", 123,
                "toolCallCount", 4,
                "toolDurationMs", 55,
                "repairRounds", 1
        ));

        var task = service.getSummary(10).getRecentTasks().get(0);
        assertEquals("routing_chat_model", task.getModelName());
        assertEquals(12L, task.getFirstTokenLatencyMs());
        assertEquals(123L, task.getTotalAiDurationMs());
        assertEquals(4, task.getToolCallCount());
        assertEquals(55L, task.getToolDurationMs());
        assertEquals(1, task.getRepairRounds());
    }
}
