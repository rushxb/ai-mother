package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.config.GenerationBenchmarkEvidenceProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReleaseGate;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReportValidator;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationBenchmarkEvidenceManagementServiceTest {

    @Test
    void mismatchedPromptBundleMustBeRejectedBeforeEvidencePersistence() {
        GenerationBenchmarkEvidenceRepository repository = mock(GenerationBenchmarkEvidenceRepository.class);
        GenerationBenchmarkEvidenceCodec codec = mock(GenerationBenchmarkEvidenceCodec.class);
        GenerationBenchmarkEvidenceSignatureService signatureService =
                mock(GenerationBenchmarkEvidenceSignatureService.class);
        GenerationBenchmarkDatasetFingerprintService datasetFingerprintService =
                mock(GenerationBenchmarkDatasetFingerprintService.class);
        GenerationBenchmarkReleaseGate releaseGate = mock(GenerationBenchmarkReleaseGate.class);
        GenerationBenchmarkReportValidator reportValidator = mock(GenerationBenchmarkReportValidator.class);
        PromptCatalog promptCatalog = mock(PromptCatalog.class);
        GenerationReleaseProvenanceProvider releaseProvenanceProvider =
                mock(GenerationReleaseProvenanceProvider.class);
        GenerationBenchmarkModelFingerprintProvider modelFingerprintProvider =
                mock(GenerationBenchmarkModelFingerprintProvider.class);
        GenerationBenchmarkEvidenceProperties properties = new GenerationBenchmarkEvidenceProperties();
        properties.setGraderFingerprint("grader-v1");
        GenerationBenchmarkEvidenceManagementService service =
                new GenerationBenchmarkEvidenceManagementService(
                        repository,
                        codec,
                        signatureService,
                        datasetFingerprintService,
                        reportValidator,
                        releaseGate,
                        new GenerationBenchmarkEvidenceProvenanceValidator(
                                promptCatalog,
                                releaseProvenanceProvider,
                                modelFingerprintProvider
                        ),
                        properties
                );
        String reportJson = "{\"totalTasks\":1}";
        String reportHash = "f".repeat(64);
        String dataset = "b".repeat(64);
        String signature = "1".repeat(64);
        GenerationBenchmarkReport report = mock(GenerationBenchmarkReport.class);
        when(codec.reportSha256(reportJson)).thenReturn(reportHash);
        when(datasetFingerprintService.currentFingerprint()).thenReturn(dataset);
        when(signatureService.verify(any(GenerationBenchmarkEvidencePayload.class), eq(signature)))
                .thenReturn(true);
        when(codec.parseReport(reportJson)).thenReturn(report);
        when(report.promptBundleId()).thenReturn("e".repeat(64));
        Instant now = Instant.now();
        GenerationBenchmarkEvidenceSubmission submission = new GenerationBenchmarkEvidenceSubmission(
                GenerationBenchmarkEvidenceProtocol.CURRENT_SIGNATURE_VERSION,
                GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE,
                "app-generation",
                "a".repeat(64),
                0L,
                dataset,
                "grader-v1",
                "c".repeat(64),
                "1".repeat(40),
                "d".repeat(64),
                "9".repeat(64),
                reportJson,
                now.minusSeconds(10),
                now.plusSeconds(3600),
                signature
        );

        assertThrows(BusinessException.class, () -> service.ingest(submission));

        verify(repository, never()).insert(any());
        verify(reportValidator).validate(report);
        verifyNoInteractions(releaseGate);
    }
}
