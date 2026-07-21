package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.config.GenerationBenchmarkEvidenceProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReleaseAssessment;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReleaseGate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Replays all trust checks at release time so evidence cannot drift or be altered in storage. */
@Component
@RequiredArgsConstructor
public class GenerationReleaseEvidenceVerifier {

    private final GenerationBenchmarkEvidenceRepository repository;
    private final GenerationBenchmarkEvidenceCodec codec;
    private final GenerationBenchmarkEvidenceSignatureService signatureService;
    private final GenerationBenchmarkDatasetFingerprintService datasetFingerprintService;
    private final GenerationBenchmarkReleaseGate releaseGate;
    private final GenerationBenchmarkEvidenceProperties properties;
    private final Clock clock = Clock.systemUTC();

    public GenerationBenchmarkEvidenceRecord requirePassed(String evidenceId,
                                                            GenerationBenchmarkEvidenceSubject subjectType,
                                                            String subjectKey,
                                                            String candidateFingerprint) {
        String normalizedId = requireEvidenceId(evidenceId);
        GenerationBenchmarkEvidenceRecord evidence = repository.findByEvidenceId(normalizedId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR, "Benchmark release evidence does not exist"));
        GenerationBenchmarkEvidencePayload payload = evidence.payload();
        if (payload == null
                || payload.subjectType() != subjectType
                || !Objects.equals(payload.subjectKey(), subjectKey)
                || !Objects.equals(payload.candidateFingerprint(), candidateFingerprint)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Benchmark evidence does not match the release candidate");
        }
        Instant now = clock.instant();
        if (!payload.expiresAt().isAfter(now)
                || !payload.datasetFingerprint().equals(datasetFingerprintService.currentFingerprint())
                || !payload.graderFingerprint().equals(properties.getGraderFingerprint())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Benchmark release evidence is stale");
        }
        if (!codec.reportSha256(evidence.reportJson()).equals(payload.reportSha256())
                || !signatureService.verify(payload, evidence.signature())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                    "Benchmark release evidence integrity verification failed");
        }
        GenerationBenchmarkReleaseAssessment assessment = releaseGate.assess(
                codec.parseReport(evidence.reportJson()));
        if (!assessment.passed()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Benchmark release gate did not pass: " + String.join(",", assessment.violations()));
        }
        return evidence;
    }

    private String requireEvidenceId(String evidenceId) {
        try {
            return UUID.fromString(evidenceId == null ? "" : evidenceId.trim()).toString();
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "evidenceId is invalid", invalid);
        }
    }
}
