package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkPromptFingerprintProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiModelEnableBenchmarkEvidenceCandidateResolverTest {

    private static final String FINGERPRINT = "a".repeat(64);
    private static final String FLEET = "b".repeat(64);
    private static final String BUNDLE = "c".repeat(64);

    private AiModelPersistenceService persistenceService;
    private AiModelConfigurationPolicy configurationPolicy;
    private AiModelCandidateFingerprintService fingerprintService;
    private AiModelFleetFingerprintService fleetFingerprintService;
    private GenerationBenchmarkPromptFingerprintProvider promptFingerprintProvider;
    private AiModelEnableBenchmarkEvidenceCandidateResolver resolver;

    @BeforeEach
    void setUp() {
        persistenceService = mock(AiModelPersistenceService.class);
        configurationPolicy = mock(AiModelConfigurationPolicy.class);
        fingerprintService = mock(AiModelCandidateFingerprintService.class);
        fleetFingerprintService = mock(AiModelFleetFingerprintService.class);
        promptFingerprintProvider = mock(GenerationBenchmarkPromptFingerprintProvider.class);
        resolver = new AiModelEnableBenchmarkEvidenceCandidateResolver(
                persistenceService,
                configurationPolicy,
                fingerprintService,
                fleetFingerprintService,
                promptFingerprintProvider);
    }

    @Test
    void resolverMustFingerprintTheNormalizedEnabledCandidate() {
        AiModelConfiguration disabled = model(0);
        AiModelConfiguration normalizedEnabled = model(1);
        when(persistenceService.findActiveById(7L)).thenReturn(disabled);
        when(configurationPolicy.normalizeAndValidate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(normalizedEnabled);
        when(fingerprintService.fingerprint(normalizedEnabled)).thenReturn(FINGERPRINT);
        when(fleetFingerprintService.fingerprintWithEnabledCandidate(normalizedEnabled))
                .thenReturn(FLEET);
        when(promptFingerprintProvider.currentDurableFingerprint()).thenReturn(BUNDLE);

        var identity = resolver.resolve(new GenerationBenchmarkEvidenceCandidate.AiModelEnable(7L));

        assertEquals(GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE, identity.subjectType());
        assertEquals("7", identity.subjectKey());
        assertEquals(FINGERPRINT, identity.candidateFingerprint());
        assertEquals(FLEET, identity.modelFingerprint());
        assertEquals(BUNDLE, identity.promptBundleFingerprint());
        ArgumentCaptor<AiModelConfiguration> candidateCaptor =
                ArgumentCaptor.forClass(AiModelConfiguration.class);
        verify(configurationPolicy).normalizeAndValidate(candidateCaptor.capture());
        assertEquals(1, candidateCaptor.getValue().getIsEnabled());
    }

    @Test
    void enabledModelMustNotBeAcceptedAsAnEnableCandidate() {
        when(persistenceService.findActiveById(7L)).thenReturn(model(1));

        assertThrows(BusinessException.class, () -> resolver.resolve(
                new GenerationBenchmarkEvidenceCandidate.AiModelEnable(7L)));

        verifyNoInteractions(
                configurationPolicy,
                fingerprintService,
                fleetFingerprintService,
                promptFingerprintProvider
        );
    }

    private AiModelConfiguration model(int enabled) {
        return AiModelConfiguration.builder()
                .id(7L)
                .modelName("模型")
                .provider("custom")
                .modelId("model")
                .baseUrl("https://8.8.8.8/v1")
                .secretRef("enc:v1:secret")
                .secretFingerprint(FINGERPRINT)
                .secretKeyId("secret")
                .maxTokens(4096)
                .temperature(0.7)
                .isEnabled(enabled)
                .modelType("chat")
                .supportsThinking(0)
                .sortOrder(0)
                .userId(1L)
                .build();
    }
}
