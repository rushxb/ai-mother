package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.config.GenerationBenchmarkEvidenceProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReleaseAssessment;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReleaseGate;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReportValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationReleaseEvidenceVerifierTest {

    private static final String EVIDENCE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String CANDIDATE = "a".repeat(64);
    private static final String DATASET = "b".repeat(64);
    private static final String REPORT_HASH = "c".repeat(64);
    private static final String REPORT_JSON = "{\"totalTasks\":1}";
    private static final String SIGNATURE = "d".repeat(64);
    private static final String GRADER = "generation-benchmark-graders-v1";

    private GenerationBenchmarkEvidenceRepository repository;
    private GenerationBenchmarkEvidenceCodec codec;
    private GenerationBenchmarkEvidenceSignatureService signatureService;
    private GenerationBenchmarkDatasetFingerprintService datasetFingerprintService;
    private GenerationBenchmarkReportValidator reportValidator;
    private GenerationBenchmarkReleaseGate releaseGate;
    private GenerationBenchmarkEvidenceProvenanceValidator provenanceValidator;
    private GenerationBenchmarkEvidenceCandidateIdentityResolver candidateIdentityResolver;
    private GenerationBenchmarkEvidenceProperties properties;
    private GenerationReleaseEvidenceVerifier verifier;

    @BeforeEach
    void setUp() {
        repository = mock(GenerationBenchmarkEvidenceRepository.class);
        codec = mock(GenerationBenchmarkEvidenceCodec.class);
        signatureService = mock(GenerationBenchmarkEvidenceSignatureService.class);
        datasetFingerprintService = mock(GenerationBenchmarkDatasetFingerprintService.class);
        reportValidator = mock(GenerationBenchmarkReportValidator.class);
        releaseGate = mock(GenerationBenchmarkReleaseGate.class);
        provenanceValidator = mock(GenerationBenchmarkEvidenceProvenanceValidator.class);
        candidateIdentityResolver = mock(GenerationBenchmarkEvidenceCandidateIdentityResolver.class);
        when(candidateIdentityResolver.resolve(any())).thenReturn(identity(CANDIDATE));
        properties = new GenerationBenchmarkEvidenceProperties();
        properties.setGraderFingerprint(GRADER);
        verifier = new GenerationReleaseEvidenceVerifier(
                repository,
                codec,
                signatureService,
                datasetFingerprintService,
                reportValidator,
                releaseGate,
                provenanceValidator,
                candidateIdentityResolver,
                properties
        );
    }

    @Test
    void validEvidenceMustBeReverifiedAtReleaseTime() {
        GenerationBenchmarkEvidenceRecord evidence = validEvidence(true);
        GenerationBenchmarkReport report = stubIntegrityAndGate(evidence, true);

        GenerationBenchmarkEvidenceRecord verified = verifier.requirePassed(
                EVIDENCE_ID,
                candidate()
        );

        assertSame(evidence, verified);
        org.mockito.Mockito.verify(reportValidator).validate(report);
        org.mockito.Mockito.verify(provenanceValidator).validate(evidence.payload(), report);
    }

    @Test
    void candidateMismatchMustRejectEvidence() {
        GenerationBenchmarkEvidenceRecord evidence = validEvidence(true);
        when(repository.findByEvidenceId(EVIDENCE_ID)).thenReturn(Optional.of(evidence));
        when(candidateIdentityResolver.resolve(any())).thenReturn(identity("e".repeat(64)));

        BusinessException exception = assertThrows(BusinessException.class, () -> verifier.requirePassed(
                EVIDENCE_ID,
                candidate()
        ));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
    }

    @Test
    void resultingModelFleetMismatchMustRejectEvidence() {
        GenerationBenchmarkEvidenceRecord evidence = validEvidence(true);
        when(repository.findByEvidenceId(EVIDENCE_ID)).thenReturn(Optional.of(evidence));
        when(candidateIdentityResolver.resolve(any())).thenReturn(
                new GenerationBenchmarkEvidenceCandidateIdentity(
                        GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE,
                        "app-generation",
                        CANDIDATE,
                        "9".repeat(64),
                        "1".repeat(64)
                ));

        assertThrows(BusinessException.class, () -> verifier.requirePassed(
                EVIDENCE_ID,
                candidate()
        ));
    }

    @Test
    void resultingPromptBundleMismatchMustRejectEvidence() {
        GenerationBenchmarkEvidenceRecord evidence = validEvidence(true);
        when(repository.findByEvidenceId(EVIDENCE_ID)).thenReturn(Optional.of(evidence));
        when(candidateIdentityResolver.resolve(any())).thenReturn(
                new GenerationBenchmarkEvidenceCandidateIdentity(
                        GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE,
                        "app-generation",
                        CANDIDATE,
                        "f".repeat(64),
                        "9".repeat(64)
                ));

        assertThrows(BusinessException.class, () -> verifier.requirePassed(
                EVIDENCE_ID,
                candidate()
        ));
    }

    @Test
    void staleDatasetMustRejectEvidence() {
        GenerationBenchmarkEvidenceRecord evidence = validEvidence(true);
        when(repository.findByEvidenceId(EVIDENCE_ID)).thenReturn(Optional.of(evidence));
        when(datasetFingerprintService.currentFingerprint()).thenReturn("e".repeat(64));

        BusinessException exception = assertThrows(BusinessException.class, () -> verify(evidence));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
    }

    @Test
    void staleGraderMustRejectEvidence() {
        GenerationBenchmarkEvidenceRecord evidence = validEvidence(true);
        when(repository.findByEvidenceId(EVIDENCE_ID)).thenReturn(Optional.of(evidence));
        when(datasetFingerprintService.currentFingerprint()).thenReturn(DATASET);
        properties.setGraderFingerprint("generation-benchmark-graders-v2");

        BusinessException exception = assertThrows(BusinessException.class, () -> verify(evidence));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
    }

    @Test
    void expiredEvidenceMustBeRejected() {
        GenerationBenchmarkEvidencePayload payload = payload(Instant.now().minusSeconds(1));
        GenerationBenchmarkEvidenceRecord evidence = evidence(payload, true);
        when(repository.findByEvidenceId(EVIDENCE_ID)).thenReturn(Optional.of(evidence));

        BusinessException exception = assertThrows(BusinessException.class, () -> verify(evidence));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
    }

    @Test
    void reportJsonTamperingMustFailIntegrityVerification() {
        GenerationBenchmarkEvidenceRecord evidence = validEvidence(true);
        when(repository.findByEvidenceId(EVIDENCE_ID)).thenReturn(Optional.of(evidence));
        when(datasetFingerprintService.currentFingerprint()).thenReturn(DATASET);
        when(codec.reportSha256(REPORT_JSON)).thenReturn("e".repeat(64));

        BusinessException exception = assertThrows(BusinessException.class, () -> verify(evidence));

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
    }

    @Test
    void persistedPassedFlagMustNotBypassCurrentReleaseGate() {
        GenerationBenchmarkEvidenceRecord evidence = validEvidence(true);
        stubIntegrityAndGate(evidence, false);

        BusinessException exception = assertThrows(BusinessException.class, () -> verify(evidence));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
    }

    @Test
    void legacyEvidenceMustNotAuthorizeANewRelease() {
        GenerationBenchmarkEvidencePayload current = payload(
                Instant.now().plusSeconds(3600));
        GenerationBenchmarkEvidencePayload legacy = new GenerationBenchmarkEvidencePayload(
                GenerationBenchmarkEvidenceProtocol.LEGACY_SIGNATURE_VERSION,
                current.subjectType(),
                current.subjectKey(),
                current.candidateFingerprint(),
                0L,
                current.datasetFingerprint(),
                current.graderFingerprint(),
                current.runtimeConfigFingerprint(),
                current.gitCommit(),
                current.modelFingerprint(),
                current.promptBundleFingerprint(),
                current.reportSha256(),
                current.evaluatedAt(),
                current.expiresAt()
        );
        GenerationBenchmarkEvidenceRecord evidence = evidence(legacy, true);
        when(repository.findByEvidenceId(EVIDENCE_ID)).thenReturn(Optional.of(evidence));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> verify(evidence));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
    }

    private GenerationBenchmarkEvidenceRecord verify(GenerationBenchmarkEvidenceRecord evidence) {
        return verifier.requirePassed(
                evidence.evidenceId(),
                candidate()
        );
    }

    private GenerationBenchmarkReport stubIntegrityAndGate(GenerationBenchmarkEvidenceRecord evidence,
                                                            boolean passed) {
        GenerationBenchmarkReport report = report();
        when(repository.findByEvidenceId(EVIDENCE_ID)).thenReturn(Optional.of(evidence));
        when(datasetFingerprintService.currentFingerprint()).thenReturn(DATASET);
        when(codec.reportSha256(REPORT_JSON)).thenReturn(REPORT_HASH);
        when(signatureService.verify(evidence.payload(), SIGNATURE)).thenReturn(true);
        when(codec.parseReport(REPORT_JSON)).thenReturn(report);
        when(releaseGate.assess(report)).thenReturn(new GenerationBenchmarkReleaseAssessment(
                passed,
                passed ? List.of() : List.of("success_rate_below_minimum"),
                report
        ));
        return report;
    }

    private GenerationBenchmarkEvidenceRecord validEvidence(boolean passed) {
        return evidence(payload(Instant.now().plusSeconds(3600)), passed);
    }

    private GenerationBenchmarkEvidenceRecord evidence(GenerationBenchmarkEvidencePayload payload,
                                                        boolean passed) {
        return new GenerationBenchmarkEvidenceRecord(
                EVIDENCE_ID,
                payload,
                REPORT_JSON,
                passed,
                passed ? List.of() : List.of("stored_failure"),
                SIGNATURE,
                Instant.now()
        );
    }

    private GenerationBenchmarkEvidencePayload payload(Instant expiresAt) {
        return new GenerationBenchmarkEvidencePayload(
                GenerationBenchmarkEvidenceProtocol.CURRENT_SIGNATURE_VERSION,
                GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE,
                "app-generation",
                CANDIDATE,
                0L,
                DATASET,
                GRADER,
                "e".repeat(64),
                "1".repeat(40),
                "f".repeat(64),
                "1".repeat(64),
                REPORT_HASH,
                expiresAt.minusSeconds(3600),
                expiresAt
        );
    }

    private GenerationBenchmarkReport report() {
        return new GenerationBenchmarkReport(
                GenerationBenchmarkReport.CURRENT_SCHEMA_VERSION,
                1,
                1,
                1,
                1.0,
                1.0,
                100,
                100,
                100,
                100,
                1,
                1,
                0,
                0,
                100,
                1,
                10,
                10,
                10,
                1,
                1.0,
                50,
                50,
                50,
                "1".repeat(64),
                "f".repeat(64),
                Map.of(),
                Map.of(),
                List.of()
        );
    }

    private GenerationBenchmarkEvidenceCandidate candidate() {
        return new GenerationBenchmarkEvidenceCandidate.PromptRelease(
                "app-generation",
                new PromptReleaseSpec("v1", "", 0)
        );
    }

    private GenerationBenchmarkEvidenceCandidateIdentity identity(String candidateFingerprint) {
        return new GenerationBenchmarkEvidenceCandidateIdentity(
                GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE,
                "app-generation",
                candidateFingerprint,
                "f".repeat(64),
                "1".repeat(64)
        );
    }
}
