package com.rush.rushaicodemother.service.trace;

import com.rush.rushaicodemother.config.AiModelInvocationLedgerProperties;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationModelInvocationRecoveryServiceTest {

    @Test
    void recoveryMustUseTheLedgerClockZoneForGraceAndLeaseObservationTime() {
        GenerationTracePersistenceService persistence = mock(GenerationTracePersistenceService.class);
        AiModelMetricsCollector metrics = mock(AiModelMetricsCollector.class);
        AiModelInvocationLedgerProperties properties = new AiModelInvocationLedgerProperties();
        properties.setRecoveryGrace(Duration.ofMinutes(2));
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-14T08:30:00Z"), ZoneId.of("Asia/Shanghai"));
        when(persistence.recoverStaleGenerationStartedModelCalls(
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(3);
        when(persistence.countStartedModelCalls()).thenReturn(7L);
        when(persistence.recoverStaleExemptStartedModelCalls(
                org.mockito.ArgumentMatchers.any(LocalDateTime.class))).thenReturn(2);
        GenerationModelInvocationRecoveryService service =
                new GenerationModelInvocationRecoveryService(
                        persistence, properties, metrics, clock);

        assertEquals(5, service.recoverStaleInvocations());

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> observedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(persistence).recoverStaleGenerationStartedModelCalls(
                cutoff.capture(), observedAt.capture());
        assertEquals(LocalDateTime.of(2026, 8, 14, 16, 28), cutoff.getValue());
        assertEquals(LocalDateTime.of(2026, 8, 14, 16, 30), observedAt.getValue());
        verify(metrics).recordInvocationRecovery("success", 5);
        assertEquals(7, service.refreshUnsettledInvocationCount());
        verify(metrics).recordUnsettledInvocationCount(7);
    }
}
