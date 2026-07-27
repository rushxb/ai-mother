package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.config.GenerationBenchmarkEvidenceProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReportValidator;
import com.rush.rushaicodemother.service.release.AiReleaseCoordinationLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationBenchmarkEvidenceEnvelopeServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-21T10:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String CANDIDATE = "b".repeat(64);
    private static final String FLEET = "c".repeat(64);
    private static final String COMMIT = "1".repeat(40);

    private GenerationBenchmarkEvidenceCodec codec;
    private GenerationBenchmarkEvidenceProperties properties;
    private GenerationBenchmarkEvidenceCandidateResolver candidateResolver;
    private GenerationBenchmarkReportValidator reportValidator;
    private AiReleaseCoordinationLock coordinationLock;
    private GenerationBenchmarkEvidenceEnvelopeService service;

    @BeforeEach
    void setUp() {
        codec = new GenerationBenchmarkEvidenceCodec(new ObjectMapper());
        properties = new GenerationBenchmarkEvidenceProperties();
        properties.setSigningSecret("s".repeat(32));
        properties.setGraderFingerprint("grader-v1");
        properties.setMaximumValidity(Duration.ofDays(7));
        candidateResolver = mock(GenerationBenchmarkEvidenceCandidateResolver.class);
        reportValidator = mock(GenerationBenchmarkReportValidator.class);
        when(candidateResolver.supports(any())).thenReturn(true);
        coordinationLock = mock(AiReleaseCoordinationLock.class);
        service = new GenerationBenchmarkEvidenceEnvelopeService(
                codec,
                reportValidator,
                datasetFingerprintService(),
                properties,
                mockProvenanceProvider(),
                new GenerationBenchmarkEvidenceSignatureService(properties),
                new GenerationBenchmarkEvidenceCandidateIdentityResolver(
                        List.of(candidateResolver)),
                coordinationLock,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void modelEnableEnvelopeMustUseTheResolverIdentity() {
        GenerationBenchmarkEvidenceCandidate candidate =
                new GenerationBenchmarkEvidenceCandidate.AiModelEnable(7L);
        when(candidateResolver.resolve(candidate)).thenReturn(
                new GenerationBenchmarkEvidenceCandidateIdentity(
                        GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE,
                        "7",
                        CANDIDATE,
                        FLEET,
                        HASH
                ));

        GenerationBenchmarkEvidenceSubmission submission = service.create(
                new GenerationBenchmarkEvidenceEnvelopeRequest(
                        candidate,
                        report(),
                        1L,
                        Duration.ofHours(12)
                )
        );

        assertEquals(GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE, submission.subjectType());
        assertEquals("7", submission.subjectKey());
        assertEquals(CANDIDATE, submission.candidateFingerprint());
        assertEquals(FLEET, submission.modelFingerprint());
        assertEquals(NOW, submission.evaluatedAt());
        assertEquals(NOW.plus(Duration.ofHours(12)), submission.expiresAt());
        assertTrue(new GenerationBenchmarkEvidenceSignatureService(properties).verify(
                new GenerationBenchmarkEvidencePayload(
                        submission.signatureVersion(),
                        submission.subjectType(), submission.subjectKey(),
                        submission.candidateFingerprint(),
                        submission.candidatePhysicalRequestCount(),
                        submission.datasetFingerprint(),
                        submission.graderFingerprint(), submission.runtimeConfigFingerprint(),
                        submission.gitCommit(), submission.modelFingerprint(),
                        submission.promptBundleFingerprint(), codec.reportSha256(submission.reportJson()),
                        submission.evaluatedAt(), submission.expiresAt()
                ),
                submission.signature()
        ));
        verify(coordinationLock).acquire();
        verify(reportValidator).validate(any(GenerationBenchmarkReport.class));
    }

    @Test
    void promptReleaseEnvelopeMustUseCandidateAndFleetFingerprints() {
        GenerationBenchmarkEvidenceCandidate candidate =
                new GenerationBenchmarkEvidenceCandidate.PromptRelease(
                        "test-prompt", new PromptReleaseSpec("v1", "v2", 10));
        when(candidateResolver.resolve(candidate)).thenReturn(
                new GenerationBenchmarkEvidenceCandidateIdentity(
                        GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE,
                        "test-prompt",
                        CANDIDATE,
                        FLEET,
                        HASH
                ));

        GenerationBenchmarkEvidenceSubmission submission = service.create(
                new GenerationBenchmarkEvidenceEnvelopeRequest(
                        candidate,
                        report(),
                        0L,
                        Duration.ofDays(1)
                )
        );

        assertEquals(GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE, submission.subjectType());
        assertEquals(CANDIDATE, submission.candidateFingerprint());
        assertEquals(FLEET, submission.modelFingerprint());
        verify(candidateResolver).resolve(candidate);
    }

    @Test
    void reportModelFleetMustMatchCandidateResultingFleet() {
        GenerationBenchmarkEvidenceCandidate candidate =
                new GenerationBenchmarkEvidenceCandidate.AiModelEnable(7L);
        when(candidateResolver.resolve(candidate)).thenReturn(
                new GenerationBenchmarkEvidenceCandidateIdentity(
                        GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE,
                        "7",
                        CANDIDATE,
                        FLEET,
                        HASH
                ));

        assertThrows(BusinessException.class, () -> service.create(
                new GenerationBenchmarkEvidenceEnvelopeRequest(
                        candidate,
                        report(HASH, "9".repeat(64)),
                        1L,
                        Duration.ofHours(12)
                )
        ));
    }

    @Test
    void reportPromptBundleMustMatchCandidateResultingBundle() {
        GenerationBenchmarkEvidenceCandidate candidate =
                new GenerationBenchmarkEvidenceCandidate.PromptRelease(
                        "test-prompt", new PromptReleaseSpec("v1", "v2", 10));
        when(candidateResolver.resolve(candidate)).thenReturn(
                new GenerationBenchmarkEvidenceCandidateIdentity(
                        GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE,
                        "test-prompt",
                        CANDIDATE,
                        FLEET,
                        HASH
                ));

        assertThrows(BusinessException.class, () -> service.create(
                new GenerationBenchmarkEvidenceEnvelopeRequest(
                        candidate,
                        report("9".repeat(64), FLEET),
                        0L,
                        Duration.ofHours(12)
                )
        ));
    }

    @Test
    void validityLongerThanPolicyMustBeRejectedBeforeStateReads() {
        assertThrows(BusinessException.class, () -> service.create(
                new GenerationBenchmarkEvidenceEnvelopeRequest(
                        new GenerationBenchmarkEvidenceCandidate.AiModelEnable(7L),
                        report(),
                        1L,
                        Duration.ofDays(8)
                )
        ));

        verifyNoInteractions(coordinationLock, candidateResolver);
    }

    @Test
    void modelCandidateWithoutPhysicalRequestMustBeRejectedBeforeStateReads() {
        assertThrows(BusinessException.class, () -> service.create(
                new GenerationBenchmarkEvidenceEnvelopeRequest(
                        new GenerationBenchmarkEvidenceCandidate.AiModelEnable(7L),
                        report(),
                        0L,
                        Duration.ofHours(12)
                )
        ));

        verifyNoInteractions(coordinationLock, candidateResolver);
    }

    private GenerationReleaseProvenanceProvider mockProvenanceProvider() {
        GenerationReleaseProvenanceProvider provider = mock(GenerationReleaseProvenanceProvider.class);
        when(provider.current()).thenReturn(new GenerationReleaseProvenanceManifest(HASH, COMMIT));
        return provider;
    }

    private GenerationBenchmarkDatasetFingerprintService datasetFingerprintService() {
        GenerationBenchmarkDatasetFingerprintService service =
                mock(GenerationBenchmarkDatasetFingerprintService.class);
        when(service.currentFingerprint()).thenReturn(HASH);
        return service;
    }

    private GenerationBenchmarkReport report() {
        return report(HASH, FLEET);
    }

    private GenerationBenchmarkReport report(String promptBundle, String modelFingerprint) {
        return new GenerationBenchmarkReport(
                GenerationBenchmarkReport.CURRENT_SCHEMA_VERSION,
                1, 1, 1, 1, 1, 100, 100, 100, 100,
                1, 1, 0, 0, 10, 1, 20, 20, 20,
                1, 1.0, 50, 50, 50,
                promptBundle, modelFingerprint, Map.of(), Map.of(), List.of()
        );
    }

}
