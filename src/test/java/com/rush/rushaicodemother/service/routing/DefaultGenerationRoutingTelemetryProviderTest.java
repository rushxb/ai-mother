package com.rush.rushaicodemother.service.routing;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationRoutingTelemetryMetricsCollector;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackRepository;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackSummary;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingTelemetryProperties;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingTelemetrySnapshot;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecutorProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLoadSnapshot;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        properties.setColdLoadTimeout(Duration.ofSeconds(1));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
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

        try (DefaultGenerationRoutingTelemetryProvider provider =
                     new DefaultGenerationRoutingTelemetryProvider(
                             traceService,
                             feedbackRepository,
                             taskRepository,
                             executorProperties,
                             properties,
                             new GenerationRoutingTelemetryMetricsCollector(meterRegistry),
                             Clock.fixed(Instant.parse("2026-07-17T08:00:00Z"), ZoneOffset.UTC))) {
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
        }
        verify(traceService, times(1)).listRecentTasksByAppId(10L, 20);
        verify(feedbackRepository, times(1)).summarizeByAppId(10L);
        verify(taskRepository, times(1)).loadCurrentLoad();
        assertEquals(1.0, meterRegistry.get("generation_routing_telemetry_operations_total")
                .tags("phase", "snapshot", "status", "loaded").counter().count());
        assertEquals(1.0, meterRegistry.get("generation_routing_telemetry_operations_total")
                .tags("phase", "snapshot", "status", "cache").counter().count());
        assertEquals(1.0, meterRegistry.get("generation_routing_telemetry_operations_total")
                .tags("phase", "load", "status", "success").counter().count());
    }

    @Test
    void slowAndSaturatedLoadsMustFailOpenWithoutCreatingUnboundedDatabaseWork()
            throws InterruptedException {
        GenerationTracePersistenceService traceService = mock(GenerationTracePersistenceService.class);
        GenerationFeedbackRepository feedbackRepository = mock(GenerationFeedbackRepository.class);
        DurableGenerationTaskRepository taskRepository = mock(DurableGenerationTaskRepository.class);
        GenerationTaskExecutorProperties executorProperties = new GenerationTaskExecutorProperties();
        GenerationRoutingTelemetryProperties properties = new GenerationRoutingTelemetryProperties();
        properties.setColdLoadTimeout(Duration.ofMillis(20));
        properties.setMaxConcurrentLoads(1);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        CountDownLatch loadCompleted = new CountDownLatch(1);

        when(traceService.listRecentTasksByAppId(10L, 20)).thenAnswer(invocation -> {
            loadStarted.countDown();
            if (!releaseLoad.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试路由遥测加载未被释放");
            }
            return List.of(task("task-slow", GenerationTaskStatus.SUCCESS, 50_000L));
        });
        when(feedbackRepository.summarizeByAppId(10L)).thenReturn(GenerationFeedbackSummary.empty());
        when(taskRepository.loadCurrentLoad()).thenAnswer(invocation -> {
            loadCompleted.countDown();
            return GenerationTaskLoadSnapshot.empty();
        });

        try (DefaultGenerationRoutingTelemetryProvider provider =
                     new DefaultGenerationRoutingTelemetryProvider(
                             traceService,
                             feedbackRepository,
                             taskRepository,
                             executorProperties,
                             properties,
                             new GenerationRoutingTelemetryMetricsCollector(meterRegistry),
                             Clock.systemUTC())) {
            AtomicReference<GenerationRoutingTelemetrySnapshot> cold = new AtomicReference<>();
            try {
                assertTimeout(Duration.ofMillis(500),
                        () -> cold.set(provider.snapshot(10L, 7L)));
                assertFalse(cold.get().available());
                assertTrue(loadStarted.await(1, TimeUnit.SECONDS));

                AtomicReference<GenerationRoutingTelemetrySnapshot> saturated = new AtomicReference<>();
                assertTimeout(Duration.ofMillis(500),
                        () -> saturated.set(provider.snapshot(11L, 7L)));
                assertFalse(saturated.get().available());
                verify(traceService, never()).listRecentTasksByAppId(eq(11L), anyInt());
            } finally {
                releaseLoad.countDown();
            }

            assertTrue(loadCompleted.await(1, TimeUnit.SECONDS));
            properties.setColdLoadTimeout(Duration.ofMillis(200));
            assertTrue(provider.snapshot(10L, 7L).available());
        }

        assertEquals(1.0, meterRegistry.get("generation_routing_telemetry_operations_total")
                .tags("phase", "snapshot", "status", "timeout").counter().count());
        assertEquals(1.0, meterRegistry.get("generation_routing_telemetry_operations_total")
                .tags("phase", "snapshot", "status", "unavailable").counter().count());
        assertEquals(1.0, meterRegistry.get("generation_routing_telemetry_operations_total")
                .tags("phase", "load", "status", "saturated").counter().count());
    }

    @Test
    void refreshFailureMustKeepARecentStaleSnapshot() throws InterruptedException {
        GenerationTracePersistenceService traceService = mock(GenerationTracePersistenceService.class);
        GenerationFeedbackRepository feedbackRepository = mock(GenerationFeedbackRepository.class);
        DurableGenerationTaskRepository taskRepository = mock(DurableGenerationTaskRepository.class);
        GenerationTaskExecutorProperties executorProperties = new GenerationTaskExecutorProperties();
        GenerationRoutingTelemetryProperties properties = new GenerationRoutingTelemetryProperties();
        properties.setColdLoadTimeout(Duration.ofMillis(50));
        properties.setCacheTtl(Duration.ofMillis(100));
        properties.setStaleRetention(Duration.ofSeconds(5));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CountDownLatch refreshAttempted = new CountDownLatch(1);
        CountDownLatch refreshHandled = new CountDownLatch(1);
        GenerationRoutingTelemetryMetricsCollector metricsCollector =
                new GenerationRoutingTelemetryMetricsCollector(meterRegistry) {
                    @Override
                    public void recordLoad(String status, Duration duration) {
                        super.recordLoad(status, duration);
                        if ("failed".equals(status)) {
                            refreshHandled.countDown();
                        }
                    }
                };

        when(traceService.listRecentTasksByAppId(10L, 20))
                .thenReturn(List.of(task("task-stale", GenerationTaskStatus.SUCCESS, 40_000L)))
                .thenAnswer(invocation -> {
                    refreshAttempted.countDown();
                    throw new IllegalStateException("测试数据库暂不可用");
                });
        when(feedbackRepository.summarizeByAppId(10L)).thenReturn(GenerationFeedbackSummary.empty());
        when(taskRepository.loadCurrentLoad()).thenReturn(GenerationTaskLoadSnapshot.empty());

        try (DefaultGenerationRoutingTelemetryProvider provider =
                     new DefaultGenerationRoutingTelemetryProvider(
                             traceService,
                             feedbackRepository,
                             taskRepository,
                             executorProperties,
                             properties,
                             metricsCollector,
                             Clock.systemUTC())) {
            GenerationRoutingTelemetrySnapshot initial = provider.snapshot(10L, 7L);
            assertTrue(initial.available());

            assertFalse(new CountDownLatch(1).await(150, TimeUnit.MILLISECONDS));
            GenerationRoutingTelemetrySnapshot stale = provider.snapshot(10L, 7L);

            assertTrue(stale.available());
            assertEquals(initial.capturedAt(), stale.capturedAt());
            assertTrue(refreshAttempted.await(1, TimeUnit.SECONDS));
            assertTrue(refreshHandled.await(1, TimeUnit.SECONDS));
            assertTrue(provider.snapshot(10L, 7L).available());
        }

        assertEquals(1.0, meterRegistry.get("generation_routing_telemetry_operations_total")
                .tags("phase", "load", "status", "failed").counter().count());
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
