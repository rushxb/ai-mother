package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.config.AiModelCircuitBreakerProperties;
import com.rush.rushaicodemother.model.event.AiModelCircuitOpenedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AiModelCircuitBreakerTest {

    @Test
    void firstProviderFailureMustOpenCircuitAndSuccessMustResetIt() {
        AiModelCircuitBreakerProperties properties = new AiModelCircuitBreakerProperties();
        properties.setFailureThreshold(1);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AiModelCircuitBreaker circuitBreaker = new AiModelCircuitBreaker(
                properties,
                publisher,
                Clock.fixed(Instant.parse("2026-07-16T08:00:00Z"), ZoneOffset.UTC)
        );

        circuitBreaker.recordFailure("primary-model", new RuntimeException("503 service unavailable"));

        assertFalse(circuitBreaker.isAvailable("primary-model"));
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        AiModelCircuitOpenedEvent opened = (AiModelCircuitOpenedEvent) eventCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("primary-model", opened.modelId());

        circuitBreaker.recordSuccess("primary-model");
        assertTrue(circuitBreaker.isAvailable("primary-model"));
    }

    @Test
    void sameModelIdFromDifferentProvidersMustUseIndependentCircuits() {
        AiModelCircuitBreakerProperties properties = new AiModelCircuitBreakerProperties();
        properties.setFailureThreshold(1);
        AiModelCircuitBreaker circuitBreaker = new AiModelCircuitBreaker(
                properties,
                mock(ApplicationEventPublisher.class),
                Clock.fixed(Instant.parse("2026-07-16T08:00:00Z"), ZoneOffset.UTC)
        );

        circuitBreaker.recordFailure("provider-a", "shared-model",
                new RuntimeException("503 service unavailable"));

        assertFalse(circuitBreaker.isAvailable("provider-a", "shared-model"));
        assertTrue(circuitBreaker.isAvailable("provider-b", "shared-model"));
    }

    @Test
    void cancelledLoserMustNotPenalizeProviderCircuit() {
        AiModelCircuitBreakerProperties properties = new AiModelCircuitBreakerProperties();
        properties.setFailureThreshold(1);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AiModelCircuitBreaker circuitBreaker = new AiModelCircuitBreaker(
                properties,
                publisher,
                Clock.fixed(Instant.parse("2026-07-16T08:00:00Z"), ZoneOffset.UTC)
        );

        circuitBreaker.recordFailure(
                "provider-a", "healthy-model", new CancellationException("hedge loser cancelled"));

        assertTrue(circuitBreaker.isAvailable("provider-a", "healthy-model"));
        verifyNoInteractions(publisher);
    }
}
