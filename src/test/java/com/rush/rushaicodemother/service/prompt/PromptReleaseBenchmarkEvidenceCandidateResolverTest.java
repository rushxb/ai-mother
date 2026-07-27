package com.rush.rushaicodemother.service.prompt;

import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseCapabilities;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRuntime;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;
import com.rush.rushaicodemother.service.aimodel.AiModelFleetFingerprintService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PromptReleaseBenchmarkEvidenceCandidateResolverTest {

    private static final String CANDIDATE = "a".repeat(64);
    private static final String FLEET = "b".repeat(64);
    private static final String BUNDLE = "c".repeat(64);

    private PromptReleaseCandidateFingerprintService candidateFingerprintService;
    private PromptReleaseRuntime runtime;
    private AiModelFleetFingerprintService modelFingerprintService;
    private PromptReleaseBenchmarkEvidenceCandidateResolver resolver;

    @BeforeEach
    void setUp() {
        candidateFingerprintService = mock(PromptReleaseCandidateFingerprintService.class);
        runtime = mock(PromptReleaseRuntime.class);
        modelFingerprintService = mock(AiModelFleetFingerprintService.class);
        resolver = new PromptReleaseBenchmarkEvidenceCandidateResolver(
                candidateFingerprintService, runtime, modelFingerprintService);
        when(runtime.capabilities()).thenReturn(new PromptReleaseCapabilities(Map.of(
                "test-prompt", Map.of("v1", "1".repeat(64), "v2", "2".repeat(64))
        )));
    }

    @Test
    void resolverMustBindPromptCandidateToTheCurrentModelFleet() {
        PromptReleaseSpec release = new PromptReleaseSpec("v1", "v2", 10);
        when(candidateFingerprintService.fingerprint("test-prompt", release))
                .thenReturn(CANDIDATE);
        when(candidateFingerprintService.promptBundleFingerprint("test-prompt", release))
                .thenReturn(BUNDLE);
        when(modelFingerprintService.currentPersistentFingerprint()).thenReturn(FLEET);

        var identity = resolver.resolve(new GenerationBenchmarkEvidenceCandidate.PromptRelease(
                " test-prompt ", release));

        assertEquals(GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE, identity.subjectType());
        assertEquals("test-prompt", identity.subjectKey());
        assertEquals(CANDIDATE, identity.candidateFingerprint());
        assertEquals(FLEET, identity.modelFingerprint());
        assertEquals(BUNDLE, identity.promptBundleFingerprint());
    }

    @Test
    void invalidCanaryMustFailBeforeFingerprinting() {
        PromptReleaseSpec release = new PromptReleaseSpec("v1", "v1", 10);

        assertThrows(BusinessException.class, () -> resolver.resolve(
                new GenerationBenchmarkEvidenceCandidate.PromptRelease("test-prompt", release)));

        verifyNoInteractions(candidateFingerprintService, modelFingerprintService);
    }
}
