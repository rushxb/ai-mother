package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkBrowserProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkWorkerProperties;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReleaseAssessment;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkService;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidateIdentity;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidateIdentityResolver;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceEnvelopeRequest;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceEnvelopeService;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceManagementService;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceRecord;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubmission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationBenchmarkWorkerExecutionServiceTest {

    private static final String CANDIDATE_FINGERPRINT = "a".repeat(64);
    private static final String MODEL_FINGERPRINT = "b".repeat(64);
    private static final String PROMPT_FINGERPRINT = "c".repeat(64);

    private GenerationBenchmarkBrowserProperties browserProperties;
    private GenerationBenchmarkBackendProperties backendProperties;
    private GenerationBenchmarkWorkerCandidateProvider candidateProvider;
    private GenerationBenchmarkCandidateRuntime candidateRuntime;
    private GenerationBenchmarkCandidateInvocationTracker invocationTracker;
    private GenerationBenchmarkEvidenceCandidateIdentityResolver identityResolver;
    private GenerationBenchmarkService benchmarkService;
    private GenerationBenchmarkEvidenceEnvelopeService envelopeService;
    private GenerationBenchmarkEvidenceManagementService managementService;
    private GenerationBenchmarkWorkerResultWriter resultWriter;
    private GenerationBenchmarkEvidenceCandidate candidate;
    private GenerationBenchmarkEvidenceCandidateIdentity identity;
    private GenerationBenchmarkReport report;
    private GenerationBenchmarkWorkerExecutionService service;

    @BeforeEach
    void setUp() {
        GenerationBenchmarkWorkerProperties properties =
                new GenerationBenchmarkWorkerProperties();
        browserProperties = new GenerationBenchmarkBrowserProperties();
        browserProperties.setEnabled(true);
        backendProperties = new GenerationBenchmarkBackendProperties();
        backendProperties.setEnabled(true);
        candidateProvider = mock(GenerationBenchmarkWorkerCandidateProvider.class);
        candidateRuntime = mock(GenerationBenchmarkCandidateRuntime.class);
        invocationTracker = mock(GenerationBenchmarkCandidateInvocationTracker.class);
        identityResolver = mock(GenerationBenchmarkEvidenceCandidateIdentityResolver.class);
        benchmarkService = mock(GenerationBenchmarkService.class);
        envelopeService = mock(GenerationBenchmarkEvidenceEnvelopeService.class);
        managementService = mock(GenerationBenchmarkEvidenceManagementService.class);
        resultWriter = mock(GenerationBenchmarkWorkerResultWriter.class);
        candidate = new GenerationBenchmarkEvidenceCandidate.AiModelEnable(7L);
        identity = identity(CANDIDATE_FINGERPRINT);
        report = GenerationBenchmarkWorkerTestFixtures.report(
                MODEL_FINGERPRINT, PROMPT_FINGERPRINT);
        when(candidateProvider.candidate()).thenReturn(candidate);
        when(identityResolver.resolve(candidate)).thenReturn(identity);
        when(invocationTracker.requireCandidateInvoked(candidate)).thenReturn(3L);
        service = new GenerationBenchmarkWorkerExecutionService(
                properties,
                browserProperties,
                backendProperties,
                candidateProvider,
                candidateRuntime,
                invocationTracker,
                identityResolver,
                benchmarkService,
                envelopeService,
                managementService,
                resultWriter
        );
    }

    @Test
    void passedCandidateMustBeSignedVerifiedStoredAndWritten() {
        when(benchmarkService.runReleaseGate()).thenReturn(
                new GenerationBenchmarkReleaseAssessment(true, List.of(), report));
        GenerationBenchmarkEvidenceSubmission submission =
                mock(GenerationBenchmarkEvidenceSubmission.class);
        when(envelopeService.create(any())).thenReturn(submission);
        GenerationBenchmarkEvidenceRecord evidence =
                mock(GenerationBenchmarkEvidenceRecord.class);
        when(evidence.passed()).thenReturn(true);
        when(evidence.evidenceId()).thenReturn("evidence-1");
        when(evidence.violations()).thenReturn(List.of());
        when(managementService.ingest(submission)).thenReturn(evidence);

        GenerationBenchmarkWorkerResult result = service.execute();

        assertEquals(GenerationBenchmarkWorkerResult.Status.PASSED, result.status());
        assertEquals("evidence-1", result.evidenceId());
        assertEquals(3L, result.candidatePhysicalRequestCount());
        verify(candidateRuntime).prepare(candidate);
        verify(invocationTracker).begin(candidate);
        verify(invocationTracker).requireCandidateInvoked(candidate);
        verify(invocationTracker).end();
        ArgumentCaptor<GenerationBenchmarkEvidenceEnvelopeRequest> requestCaptor =
                ArgumentCaptor.forClass(GenerationBenchmarkEvidenceEnvelopeRequest.class);
        verify(envelopeService).create(requestCaptor.capture());
        assertEquals(candidate, requestCaptor.getValue().candidate());
        assertEquals(report, requestCaptor.getValue().report());
        assertEquals(3L, requestCaptor.getValue().candidatePhysicalRequestCount());
        verify(managementService).ingest(submission);
        verify(resultWriter).write(result);
    }

    @Test
    void rejectedCandidateMustWriteReportWithoutSigningEvidence() {
        when(benchmarkService.runReleaseGate()).thenReturn(
                new GenerationBenchmarkReleaseAssessment(
                        false, List.of("success_rate_below_minimum"), report));

        assertThrows(GenerationBenchmarkWorkerRejectedException.class, service::execute);

        ArgumentCaptor<GenerationBenchmarkWorkerResult> resultCaptor =
                ArgumentCaptor.forClass(GenerationBenchmarkWorkerResult.class);
        verify(resultWriter).write(resultCaptor.capture());
        assertEquals(GenerationBenchmarkWorkerResult.Status.REJECTED,
                resultCaptor.getValue().status());
        assertEquals(List.of("success_rate_below_minimum"),
                resultCaptor.getValue().violations());
        verifyNoInteractions(envelopeService, managementService);
    }

    @Test
    void candidateDriftMustFailBeforeSigningOrWritingFinalResult() {
        when(identityResolver.resolve(candidate)).thenReturn(
                identity,
                identity("d".repeat(64))
        );
        when(benchmarkService.runReleaseGate()).thenReturn(
                new GenerationBenchmarkReleaseAssessment(true, List.of(), report));

        assertThrows(IllegalStateException.class, service::execute);

        verify(resultWriter, never()).write(any());
        verifyNoInteractions(envelopeService, managementService);
    }

    @Test
    void missingRuntimeGraderMustFailBeforePreparingOutput() {
        backendProperties.setEnabled(false);

        assertThrows(IllegalStateException.class, service::execute);

        verify(resultWriter, never()).prepare();
        verify(candidateRuntime, never()).prepare(any());
        verify(invocationTracker, never()).requireCandidateInvoked(any());
    }

    private GenerationBenchmarkEvidenceCandidateIdentity identity(
            String candidateFingerprint) {
        return new GenerationBenchmarkEvidenceCandidateIdentity(
                GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE,
                "7",
                candidateFingerprint,
                MODEL_FINGERPRINT,
                PROMPT_FINGERPRINT
        );
    }
}
