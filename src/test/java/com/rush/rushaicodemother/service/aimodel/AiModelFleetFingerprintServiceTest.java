package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelFleetFingerprintServiceTest {

    private AiModelPersistenceService persistenceService;
    private AiModelEnabledConfigurationSource configurationSource;
    private AiModelRuntimeProperties runtimeProperties;
    private AiModelFleetFingerprintService service;

    @BeforeEach
    void setUp() {
        persistenceService = mock(AiModelPersistenceService.class);
        configurationSource = mock(AiModelEnabledConfigurationSource.class);
        runtimeProperties = new AiModelRuntimeProperties();
        service = new AiModelFleetFingerprintService(
                configurationSource,
                persistenceService,
                new AiModelCandidateFingerprintService(),
                runtimeProperties
        );
    }

    @Test
    void fingerprintMustBeIndependentOfRepositoryOrdering() {
        AiModelConfiguration first = configuration(1L, "chat", 0.2);
        AiModelConfiguration second = configuration(2L, "reasoning", 0.3);
        when(configurationSource.findEnabled(null)).thenReturn(List.of(first, second));
        String ordered = service.currentFingerprint();
        when(configurationSource.findEnabled(null)).thenReturn(List.of(second, first));

        assertEquals(ordered, service.currentFingerprint());
    }

    @Test
    void modelConfigurationDriftMustChangeFleetFingerprint() {
        when(configurationSource.findEnabled(null)).thenReturn(
                List.of(configuration(1L, "chat", 0.2)));
        String baseline = service.currentFingerprint();
        when(configurationSource.findEnabled(null)).thenReturn(
                List.of(configuration(1L, "chat", 0.8)));

        assertNotEquals(baseline, service.currentFingerprint());
    }

    @Test
    void enabledCandidateMustBeIncludedInResultingFleetFingerprint() {
        AiModelConfiguration existing = configuration(1L, "chat", 0.2);
        AiModelConfiguration candidate = configuration(2L, "reasoning", 0.3);
        when(configurationSource.findEnabled(null)).thenReturn(List.of(existing));
        when(persistenceService.findEnabled(null)).thenReturn(List.of(existing));
        String current = service.currentFingerprint();

        String resulting = service.fingerprintWithEnabledCandidate(candidate);

        assertNotEquals(current, resulting);
        when(configurationSource.findEnabled(null)).thenReturn(List.of(existing, candidate));
        assertEquals(service.currentFingerprint(), resulting);
    }

    @Test
    void missingEnabledModelsMustFailClosed() {
        when(configurationSource.findEnabled(null)).thenReturn(List.of());

        assertThrows(BusinessException.class, service::currentFingerprint);
    }

    @Test
    void failoverAndHedgePolicyDriftMustChangeFleetFingerprint() {
        when(configurationSource.findEnabled(null)).thenReturn(
                List.of(configuration(1L, "chat", 0.2)));
        String baseline = service.currentFingerprint();

        runtimeProperties.setFailoverMaxCandidates(3);
        String failoverChanged = service.currentFingerprint();
        assertNotEquals(baseline, failoverChanged);

        runtimeProperties.setFirstTokenHedgeEnabled(true);
        String hedgeEnabled = service.currentFingerprint();
        assertNotEquals(failoverChanged, hedgeEnabled);

        runtimeProperties.setFirstTokenHedgeDelay(Duration.ofSeconds(2));
        String delayChanged = service.currentFingerprint();
        assertNotEquals(hedgeEnabled, delayChanged);

        runtimeProperties.setFirstTokenHedgeRequireDistinctProvider(false);
        assertNotEquals(delayChanged, service.currentFingerprint());
    }

    private AiModelConfiguration configuration(long id, String modelType, double temperature) {
        return AiModelConfiguration.builder()
                .id(id)
                .modelName("Model " + id)
                .provider("custom")
                .modelId("model-" + id)
                .baseUrl("https://models.example.com/v1")
                .secretFingerprint(String.valueOf(id).repeat(64))
                .maxTokens(4096)
                .temperature(temperature)
                .isEnabled(1)
                .modelType(modelType)
                .supportsThinking("reasoning".equals(modelType) ? 1 : 0)
                .sortOrder((int) id)
                .configJson("{\"protocol\":\"openai_chat_completions\"}")
                .build();
    }
}
