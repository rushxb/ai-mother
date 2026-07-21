package com.rush.rushaicodemother.service.routing;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackRepository;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackSummary;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingTelemetryProperties;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingTelemetrySnapshot;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecutorProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLoadSnapshot;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultGenerationRoutingTelemetryProviderTest {

    @Test
    void shouldAggregateTaskFeedbackAndRuntimeLoadWithCache() {
        GenerationTracePersistenceService traceService = mock(GenerationTracePersistenceService.class);
        GenerationFeedbackRepository feedbackRepository = mock(GenerationFeedbackRepository.class);
        DurableGenerationTaskRepository taskRepository = mock(DurableGenerationTaskRepository.class);
        GenerationTaskExecutorProperties executorProperties = new GenerationTaskExecutorProperties();
        executorProperties.setMaxConcurrency(4);
        executorProperties.setQueueCapacity(8);
        GenerationRoutingTelemetryProperties properties = new GenerationRoutingTelemetryProperties();
        DefaultGenerationRoutingTelemetryProvider provider = new DefaultGenerationRoutingTelemetryProvider(
                traceService,
                feedbackRepository,
                taskRepository,
                executorProperties,
                properties,
                Clock.fixed(Instant.parse("2026-07-17T08:00:00Z"), ZoneOffset.UTC)
        );
        when(traceService.listRecentTasksByAppId(10L, 20)).thenReturn(List.of(
                task("task-1", GenerationTaskStatus.SUCCESS, 120_000L),
                task("task-2", GenerationTaskStatus.FAILED, 180_000L),
                task("task-3", GenerationTaskStatus.DEADLINE_EXCEEDED, 300_000L),
                task("task-4", GenerationTaskStatus.CANCELLED, null)
        ));
        when(feedbackRepository.summarizeByAppId(10L))
                .thenReturn(new GenerationFeedbackSummary(4, 2, 2.75));
        when(taskRepository.loadCurrentLoad())
                .thenReturn(new GenerationTaskLoadSnapshot(4, 3, 1));

        GenerationRoutingTelemetrySnapshot first = provider.snapshot(10L, 7L);
        GenerationRoutingTelemetrySnapshot cached = provider.snapshot(10L, 7L);

        assertTrue(first.available());
        assertEquals(4, first.recentTaskCount());
        assertEquals(2, first.failedTaskCount());
        assertEquals(200_000L, first.averageDurationMs());
        assertEquals(0.5, first.failureRate());
        assertEquals(0.5, first.lowRatingRate());
        assertEquals(0.75, first.runningPressure());
        assertEquals(0.5, first.queuePressure());
        assertEquals(first, cached);
        verify(traceService, times(1)).listRecentTasksByAppId(10L, 20);
        verify(feedbackRepository, times(1)).summarizeByAppId(10L);
        verify(taskRepository, times(1)).loadCurrentLoad();
    }

    private GenerationTracePersistenceService.TaskRecord task(String taskId,
                                                               GenerationTaskStatus status,
                                                               Long durationMs) {
        LocalDateTime now = LocalDateTime.of(2026, 7, 17, 10, 0);
        return new GenerationTracePersistenceService.TaskRecord(
                1L,
                taskId,
                10L,
                7L,
                "vue_project",
                "vue_project",
                status,
                "completed",
                "",
                "prompt",
                "prompt",
                true,
                "build",
                "agent_edit",
                now.minusMinutes(5),
                now,
                durationMs,
                "",
                "",
                now
        );
    }
}
