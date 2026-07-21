package com.rush.rushaicodemother.orchestration.preview;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GenerationPreviewMilestoneServiceTest {

    @Test
    void publishesFirstRuntimePreviewExactlyOnce() {
        GenerationPerformanceMonitorService performance = mock(GenerationPerformanceMonitorService.class);
        GenerationOrchestrationMetricsCollector metrics = mock(GenerationOrchestrationMetricsCollector.class);
        GenerationEventPublisher publisher = mock(GenerationEventPublisher.class);
        GenerationPreviewMilestoneService service =
                new GenerationPreviewMilestoneService(performance, metrics, publisher);
        GenerationSession session = session();

        assertTrue(service.publishRuntimeReady(session, CodeGenTypeEnum.VUE_PROJECT));
        assertFalse(service.publishRuntimeReady(session, CodeGenTypeEnum.VUE_PROJECT));

        GenerationStreamEvent event = session.asFlux().blockFirst(Duration.ofSeconds(1));
        assertEquals(GenerationStreamEvent.FIRST_PREVIEW_READY, event.getType());
        assertEquals("runtime", event.getData().get("previewLevel"));
        verify(publisher, times(1)).publishSafely(
                any(GenerationTaskRequest.class), eq(GenerationEventType.FIRST_PREVIEW_READY), any(), any());
        verify(metrics, times(1)).recordSlaOutcome(
                "create", "first_preview", "met", "within_deadline");
    }

    private GenerationSession session() {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 2);
        }
        GenerationExecutionContext context = new GenerationExecutionContext(
                "preview-task", 1L, 2L, Instant.parse("2026-07-17T00:00:00Z"),
                new GenerationExecutionLimits(
                        Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofMillis(500), budgets),
                Clock.fixed(Instant.parse("2026-07-17T00:00:30Z"), ZoneOffset.UTC));
        App app = new App();
        app.setId(1L);
        User user = new User();
        user.setId(2L);
        GenerationSession session = new GenerationSession(null, context);
        session.bindTaskRequest(new GenerationTaskRequest(app, "build", user));
        session.recordRoute("create");
        return session;
    }
}
