package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.config.GenerationTerminalEffectProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.monitor.GenerationTerminalEffectMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTerminalEffectManagementServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T08:00:00Z");

    @Test
    void inspectMustUseConfiguredDeadLetterThresholdAndRefreshMetrics() {
        GenerationTerminalEffectRepository repository = mock(GenerationTerminalEffectRepository.class);
        GenerationTerminalEffectProperties properties = new GenerationTerminalEffectProperties();
        properties.setMaxAttempts(7);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GenerationTerminalEffectMetricsCollector metrics =
                new GenerationTerminalEffectMetricsCollector(registry);
        GenerationTerminalEffectBacklog backlog = new GenerationTerminalEffectBacklog(
                4, 2, 1, 3, NOW.minusSeconds(90));
        when(repository.inspectBacklog(NOW, 7)).thenReturn(backlog);
        GenerationTerminalEffectManagementService management = management(
                repository, properties, metrics);

        GenerationTerminalEffectManagementService.Snapshot snapshot = management.inspect();

        assertEquals(backlog, snapshot.backlog());
        assertEquals(NOW, snapshot.observedAt());
        assertEquals(3, registry.get("generation_terminal_outbox_dead_letter").gauge().value());
        assertEquals(1, registry.get("generation_terminal_outbox_backlog_refresh_total")
                .tag("status", "success").counter().count());
    }

    @Test
    void replayMustTargetExactExecutionAndRecordOperatorAuditThroughRepository() {
        GenerationTerminalEffectRepository repository = mock(GenerationTerminalEffectRepository.class);
        GenerationTerminalEffectProperties properties = new GenerationTerminalEffectProperties();
        when(repository.replayDeadLetter("task-1", 9L, 88L, NOW, 10)).thenReturn(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GenerationTerminalEffectManagementService management = management(
                repository, properties, new GenerationTerminalEffectMetricsCollector(registry));

        GenerationTerminalEffectManagementService.ReplayResult result =
                management.replayDeadLetter("task-1", 9L, 88L);

        assertEquals("task-1", result.taskId());
        assertEquals(9L, result.executionEpoch());
        assertEquals(NOW, result.requestedAt());
        verify(repository).replayDeadLetter("task-1", 9L, 88L, NOW, 10);
        assertEquals(1, registry.get("generation_terminal_outbox_items_total")
                .tag("outcome", "replayed").counter().count());
    }

    @Test
    void replayMustRejectNonDeadLetterOrConcurrentMutation() {
        GenerationTerminalEffectRepository repository = mock(GenerationTerminalEffectRepository.class);
        GenerationTerminalEffectProperties properties = new GenerationTerminalEffectProperties();
        when(repository.replayDeadLetter("task-1", 9L, 88L, NOW, 10)).thenReturn(false);
        GenerationTerminalEffectManagementService management = management(
                repository, properties, GenerationTerminalEffectMetricsCollector.noOp());

        assertThrows(BusinessException.class,
                () -> management.replayDeadLetter("task-1", 9L, 88L));
    }

    private GenerationTerminalEffectManagementService management(
            GenerationTerminalEffectRepository repository,
            GenerationTerminalEffectProperties properties,
            GenerationTerminalEffectMetricsCollector metrics) {
        return new GenerationTerminalEffectManagementService(
                repository, properties, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
