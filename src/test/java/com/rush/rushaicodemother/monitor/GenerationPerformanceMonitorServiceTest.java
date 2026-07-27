package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.monitor.span.GenerationSpanObservation;
import com.rush.rushaicodemother.monitor.span.GenerationSpanSink;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationPerformanceMonitorServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T04:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void shouldExposePhaseFourRuntimeTelemetry() {
        GenerationPerformanceMonitorService service = new GenerationPerformanceMonitorService();
        service.startTask("task-1", 1L, 2L, "agent_edit", "vue_project", Instant.now(),
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

        var task = service.getSummary(10).getRecentTasks().getFirst();
        assertEquals("routing_chat_model", task.getModelName());
        assertEquals(12L, task.getFirstTokenLatencyMs());
        assertEquals(123L, task.getTotalAiDurationMs());
        assertEquals(4, task.getToolCallCount());
        assertEquals(55L, task.getToolDurationMs());
        assertEquals(1, task.getRepairRounds());
    }

    @Test
    void routeTransitionMustPreserveOriginalStartSpansAndCreateTelemetry() {
        GenerationPerformanceMonitorService service = service(List.of());
        GenerationModeDecision createDecision = new GenerationModeDecision(
                GenerationMode.CREATE,
                0.95,
                "create first",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD,
                ""
        );
        Instant createStartedAt = NOW.minusSeconds(30);
        service.startTask(
                "task-fallback", 1L, 2L, "create", "vue_project",
                createStartedAt, createDecision);
        service.recordSpan(
                "task-fallback", "create_template_runtime", GenerationSpanCategory.PIPELINE,
                "fallback", Duration.ofSeconds(5), "create_generation_failed");
        service.recordCreateTelemetry("task-fallback", Map.of(
                "baseTemplate", "vue-base",
                "patchCount", 3,
                "fallback", true
        ));

        GenerationModeDecision heavyDecision = createDecision.withFallback(
                GenerationMode.HEAVY_EXPERT, "create_generation_failed");
        service.startTask(
                "task-fallback", 1L, 2L, "heavy_generation", "vue_project",
                NOW, heavyDecision);
        service.recordRuntimeTelemetry("task-fallback", Map.of("modelName", "expert-model"));

        var tasks = service.getSummary(10).getRecentTasks();
        assertEquals(1, tasks.size());
        var task = tasks.getFirst();
        assertEquals("heavy_generation", task.getRoute());
        assertEquals("heavy_expert", task.getMode());
        assertEquals(LocalDateTime.ofInstant(createStartedAt, ZoneId.systemDefault()), task.getStartTime());
        assertEquals("vue-base", task.getBaseTemplate());
        assertTrue(task.getCreateFallback());
        assertEquals("expert-model", task.getModelName());
        assertEquals(1, task.getSpans().size());
        assertEquals("create_template_runtime", task.getSpans().getFirst().getStage());
    }

    @Test
    void shouldPersistSpanEvenWhenInMemoryTaskWasNotStarted() {
        List<GenerationSpanObservation> captured = new ArrayList<>();
        GenerationPerformanceMonitorService service = service(List.of(captured::add));

        service.recordSpan("task-queue", "queue_wait", GenerationSpanCategory.QUEUE,
                "success", Duration.ofSeconds(5), "heavy_generation");

        assertEquals(1, captured.size());
        GenerationSpanObservation observation = captured.getFirst();
        assertEquals(NOW.minusSeconds(5), observation.startedAt());
        assertEquals(NOW, observation.endedAt());
        assertEquals(5_000L, observation.durationMs());
        assertEquals(GenerationSpanCategory.QUEUE, observation.category());
        assertFalse(observation.spanId().isBlank());
    }

    @Test
    void shouldBroadcastToRemainingSinksWhenOneSinkFails() {
        AtomicInteger successfulWrites = new AtomicInteger();
        GenerationSpanSink failingSink = observation -> {
            throw new IllegalStateException("database password must not escape");
        };
        GenerationSpanSink healthySink = observation -> successfulWrites.incrementAndGet();
        GenerationPerformanceMonitorService service = service(List.of(failingSink, healthySink));

        service.recordSpan("task-broadcast", "build", GenerationSpanCategory.BUILD,
                "success", Duration.ofMillis(12), "ok");

        assertEquals(1, successfulWrites.get());
    }

    @Test
    void spanTimerMustRecordOnlyOnceAndPreserveCategory() {
        List<GenerationSpanObservation> captured = new ArrayList<>();
        GenerationPerformanceMonitorService service = service(List.of(captured::add));
        GenerationPerformanceMonitorService.SpanTimer timer =
                service.startSpan("task-once", "llm_generation", GenerationSpanCategory.MODEL);

        timer.success();
        timer.failed("late failure");
        timer.close();

        assertEquals(1, captured.size());
        assertEquals(GenerationSpanCategory.MODEL, captured.getFirst().category());
        assertEquals("success", captured.getFirst().status());
    }

    @Test
    void detailMustBeBoundedBeforeItReachesPersistenceSinks() {
        List<GenerationSpanObservation> captured = new ArrayList<>();
        GenerationPerformanceMonitorService service = service(List.of(captured::add));

        service.recordSpan("task-detail", "validation", GenerationSpanCategory.VALIDATION,
                "failed", Duration.ZERO, "x".repeat(2_000));

        org.junit.jupiter.api.Assertions.assertTrue(
                captured.getFirst().detail().length() <= GenerationSpanObservation.MAX_DETAIL_LENGTH);
        assertNotNull(captured.getFirst().detail());
    }

    private GenerationPerformanceMonitorService service(List<GenerationSpanSink> sinks) {
        return new GenerationPerformanceMonitorService(sinks, FIXED_CLOCK);
    }
}
