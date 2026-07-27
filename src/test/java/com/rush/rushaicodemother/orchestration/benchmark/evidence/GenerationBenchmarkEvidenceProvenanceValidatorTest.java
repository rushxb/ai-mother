package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationBenchmarkEvidenceProvenanceValidatorTest {

    private static final String CANDIDATE = "a".repeat(64);
    private static final String BUNDLE = "b".repeat(64);
    private static final String RUNTIME = "c".repeat(64);
    private static final String GIT_COMMIT = "d".repeat(40);
    private static final String MODEL_FLEET = "e".repeat(64);

    private PromptCatalog promptCatalog;
    private GenerationReleaseProvenanceProvider releaseProvenanceProvider;
    private GenerationBenchmarkModelFingerprintProvider modelFingerprintProvider;
    private GenerationBenchmarkEvidenceProvenanceValidator validator;

    @BeforeEach
    void setUp() {
        promptCatalog = mock(PromptCatalog.class);
        releaseProvenanceProvider = mock(GenerationReleaseProvenanceProvider.class);
        modelFingerprintProvider = mock(GenerationBenchmarkModelFingerprintProvider.class);
        when(promptCatalog.bundleId()).thenReturn(BUNDLE);
        when(releaseProvenanceProvider.current()).thenReturn(
                new GenerationReleaseProvenanceManifest(RUNTIME, GIT_COMMIT));
        when(modelFingerprintProvider.currentFingerprint()).thenReturn(MODEL_FLEET);
        validator = new GenerationBenchmarkEvidenceProvenanceValidator(
                promptCatalog,
                releaseProvenanceProvider,
                modelFingerprintProvider
        );
    }

    @Test
    void reportBundleMustMatchSignedEvidence() {
        GenerationBenchmarkEvidencePayload payload = payload(
                GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE, BUNDLE, CANDIDATE);
        GenerationBenchmarkReport report = mock(GenerationBenchmarkReport.class);
        when(report.promptBundleId()).thenReturn("c".repeat(64));

        assertThrows(BusinessException.class, () -> validator.validate(payload, report));
    }

    @Test
    void modelEnableEvidenceMustUseCurrentPromptBundleAndReportedFleet() {
        GenerationBenchmarkEvidencePayload payload = payload(
                GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE, BUNDLE, CANDIDATE);
        GenerationBenchmarkReport report = report(BUNDLE);

        assertDoesNotThrow(() -> validator.validate(payload, report));
        verifyNoInteractions(modelFingerprintProvider);
    }

    @Test
    void modelEnableEvidenceMustRejectStalePromptBundle() {
        GenerationBenchmarkEvidencePayload payload = payload(
                GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE, "c".repeat(64), CANDIDATE);
        GenerationBenchmarkReport report = report(payload.promptBundleFingerprint());

        assertThrows(BusinessException.class, () -> validator.validate(payload, report));
    }

    @Test
    void reportModelFleetMustMatchSignedEvidence() {
        GenerationBenchmarkEvidencePayload payload = payload(
                GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE,
                BUNDLE,
                CANDIDATE,
                "d".repeat(64));
        GenerationBenchmarkReport report = report(BUNDLE, MODEL_FLEET);

        assertThrows(BusinessException.class, () -> validator.validate(payload, report));
    }

    @Test
    void promptReleaseEvidenceMayTargetAProposedPromptBundle() {
        GenerationBenchmarkEvidencePayload payload = payload(
                GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE, "c".repeat(64), CANDIDATE);
        GenerationBenchmarkReport report = report(payload.promptBundleFingerprint());

        assertDoesNotThrow(() -> validator.validate(payload, report));
    }

    @Test
    void evidenceMustRejectRuntimeConfigurationDrift() {
        GenerationBenchmarkEvidencePayload payload = payload(
                GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE, BUNDLE, CANDIDATE);
        when(releaseProvenanceProvider.current()).thenReturn(
                new GenerationReleaseProvenanceManifest("f".repeat(64), GIT_COMMIT));

        assertThrows(BusinessException.class, () -> validator.validate(
                payload, report(BUNDLE, payload.modelFingerprint())));
    }

    @Test
    void evidenceMustRejectGitCommitDrift() {
        GenerationBenchmarkEvidencePayload payload = payload(
                GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE, BUNDLE, CANDIDATE);
        when(releaseProvenanceProvider.current()).thenReturn(
                new GenerationReleaseProvenanceManifest(RUNTIME, "f".repeat(40)));

        assertThrows(BusinessException.class, () -> validator.validate(
                payload, report(BUNDLE, payload.modelFingerprint())));
    }

    @Test
    void promptReleaseEvidenceMustRejectModelFleetDrift() {
        GenerationBenchmarkEvidencePayload payload = payload(
                GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE,
                BUNDLE,
                CANDIDATE,
                "f".repeat(64));

        assertThrows(BusinessException.class, () -> validator.validate(
                payload, report(BUNDLE, payload.modelFingerprint())));
    }

    private GenerationBenchmarkEvidencePayload payload(
            GenerationBenchmarkEvidenceSubject subject,
            String bundle,
            String candidate
    ) {
        return payload(subject, bundle, candidate, MODEL_FLEET);
    }

    private GenerationBenchmarkEvidencePayload payload(
            GenerationBenchmarkEvidenceSubject subject,
            String bundle,
            String candidate,
            String modelFingerprint
    ) {
        return new GenerationBenchmarkEvidencePayload(
                GenerationBenchmarkEvidenceProtocol.CURRENT_SIGNATURE_VERSION,
                subject,
                "subject",
                candidate,
                subject == GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE ? 1L : 0L,
                "e".repeat(64),
                "grader",
                RUNTIME,
                GIT_COMMIT,
                modelFingerprint,
                bundle,
                "2".repeat(64),
                java.time.Instant.now().minusSeconds(10),
                java.time.Instant.now().plusSeconds(3600)
        );
    }

    private GenerationBenchmarkReport report(String bundle) {
        return report(bundle, MODEL_FLEET);
    }

    private GenerationBenchmarkReport report(String bundle, String modelFingerprint) {
        GenerationBenchmarkReport report = mock(GenerationBenchmarkReport.class);
        when(report.promptBundleId()).thenReturn(bundle);
        when(report.modelFingerprint()).thenReturn(modelFingerprint);
        return report;
    }
}
