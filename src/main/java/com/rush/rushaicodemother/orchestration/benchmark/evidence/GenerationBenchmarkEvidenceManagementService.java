package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.config.GenerationBenchmarkEvidenceProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReleaseAssessment;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReleaseGate;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Verifies and stores signed immutable evidence without running a long benchmark in a release request. */
@Service
@RequiredArgsConstructor
public class GenerationBenchmarkEvidenceManagementService {

    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final GenerationBenchmarkEvidenceRepository repository;
    private final GenerationBenchmarkEvidenceCodec codec;
    private final GenerationBenchmarkEvidenceSignatureService signatureService;
    private final GenerationBenchmarkDatasetFingerprintService datasetFingerprintService;
    private final GenerationBenchmarkReleaseGate releaseGate;
    private final GenerationBenchmarkEvidenceProperties properties;
    private final Clock clock = Clock.systemUTC();

    public GenerationBenchmarkEvidenceRecord ingest(GenerationBenchmarkEvidenceSubmission submission) {
        if (submission == null || submission.subjectType() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Benchmark evidence is incomplete");
        }
        String reportJson = requireReport(submission.reportJson());
        GenerationBenchmarkEvidencePayload payload = payload(submission, codec.reportSha256(reportJson));
        validateEnvironmentAndTime(payload, clock.instant());
        if (!signatureService.verify(payload, submission.signature())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "Benchmark evidence signature is invalid");
        }
        GenerationBenchmarkReport report = codec.parseReport(reportJson);
        GenerationBenchmarkReleaseAssessment assessment = releaseGate.assess(report);
        GenerationBenchmarkEvidenceRecord evidence = new GenerationBenchmarkEvidenceRecord(
                UUID.randomUUID().toString(),
                payload,
                reportJson,
                assessment.passed(),
                assessment.violations(),
                submission.signature().toLowerCase(Locale.ROOT),
                clock.instant()
        );
        repository.insert(evidence);
        return evidence;
    }

    public GenerationBenchmarkEvidenceRecord get(String evidenceId) {
        String normalized = requireEvidenceId(evidenceId);
        return repository.findByEvidenceId(normalized)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR, "Benchmark evidence does not exist"));
    }

    private GenerationBenchmarkEvidencePayload payload(GenerationBenchmarkEvidenceSubmission submission,
                                                       String reportSha256) {
        return new GenerationBenchmarkEvidencePayload(
                submission.subjectType(),
                requireText(submission.subjectKey(), 128, "subjectKey"),
                requireSha256(submission.candidateFingerprint(), "candidateFingerprint"),
                requireSha256(submission.datasetFingerprint(), "datasetFingerprint"),
                requireText(submission.graderFingerprint(), 128, "graderFingerprint"),
                requireSha256(submission.runtimeConfigFingerprint(), "runtimeConfigFingerprint"),
                requireGitCommit(submission.gitCommit()),
                requireSha256(submission.modelFingerprint(), "modelFingerprint"),
                requireSha256(submission.promptBundleFingerprint(), "promptBundleFingerprint"),
                reportSha256,
                submission.evaluatedAt(),
                submission.expiresAt()
        );
    }

    private void validateEnvironmentAndTime(GenerationBenchmarkEvidencePayload payload, Instant now) {
        if (!payload.datasetFingerprint().equals(datasetFingerprintService.currentFingerprint())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Benchmark evidence dataset fingerprint is stale");
        }
        if (!payload.graderFingerprint().equals(properties.getGraderFingerprint())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Benchmark evidence grader fingerprint is stale");
        }
        if (payload.evaluatedAt() == null || payload.expiresAt() == null
                || payload.evaluatedAt().isAfter(now.plus(MAX_CLOCK_SKEW))
                || !payload.expiresAt().isAfter(now)
                || payload.expiresAt().isAfter(payload.evaluatedAt().plus(properties.getMaximumValidity()))) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Benchmark evidence validity window is invalid");
        }
    }

    private String requireReport(String reportJson) {
        if (reportJson == null || reportJson.isBlank()
                || reportJson.getBytes(StandardCharsets.UTF_8).length > properties.getMaximumReportBytes()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "Benchmark evidence report is empty or too large");
        }
        return reportJson;
    }

    private String requireSha256(String value, String field) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, field + " must be SHA-256");
        }
        return normalized;
    }

    private String requireGitCommit(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{7,64}")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "gitCommit is invalid");
        }
        return normalized;
    }

    private String requireText(String value, int maximumLength, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > maximumLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, field + " is invalid");
        }
        return normalized;
    }

    private String requireEvidenceId(String evidenceId) {
        try {
            return UUID.fromString(evidenceId == null ? "" : evidenceId.trim()).toString();
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "evidenceId is invalid", invalid);
        }
    }
}
