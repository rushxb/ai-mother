package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.config.GenerationBenchmarkEvidenceProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReleaseAssessment;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReleaseGate;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReportValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** 验签并存储不可变证据，发布请求本身不执行耗时 Benchmark。 */
@Service
@RequiredArgsConstructor
public class GenerationBenchmarkEvidenceManagementService {

    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final GenerationBenchmarkEvidenceRepository repository;
    private final GenerationBenchmarkEvidenceCodec codec;
    private final GenerationBenchmarkEvidenceSignatureService signatureService;
    private final GenerationBenchmarkDatasetFingerprintService datasetFingerprintService;
    private final GenerationBenchmarkReportValidator reportValidator;
    private final GenerationBenchmarkReleaseGate releaseGate;
    private final GenerationBenchmarkEvidenceProvenanceValidator provenanceValidator;
    private final GenerationBenchmarkEvidenceProperties properties;
    private final Clock clock = Clock.systemUTC();

    /**
 * 接收、校验并持久化外部基准证据。
 *
 * @param submission 提交
 * @return 方法执行结果
 */
    public GenerationBenchmarkEvidenceRecord ingest(GenerationBenchmarkEvidenceSubmission submission) {
        if (submission == null || submission.subjectType() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Benchmark 证据不完整");
        }
        String reportJson = requireReport(submission.reportJson());
        GenerationBenchmarkEvidencePayload payload = payload(submission, codec.reportSha256(reportJson));
        requireCurrentExecutionAttestation(payload);
        validateEnvironmentAndTime(payload, clock.instant());
        if (!signatureService.verify(payload, submission.signature())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "Benchmark 证据签名无效");
        }
        GenerationBenchmarkReport report = codec.parseReport(reportJson);
        reportValidator.validate(report);
        provenanceValidator.validate(payload, report);
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

    /**
 * 获取指定资源。
 *
 * @param evidenceId 证据编号
 * @return 方法执行结果
 */
    public GenerationBenchmarkEvidenceRecord get(String evidenceId) {
        String normalized = requireEvidenceId(evidenceId);
        return repository.findByEvidenceId(normalized)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR, "Benchmark 证据不存在"));
    }

    /** 返回载荷。 */
    private GenerationBenchmarkEvidencePayload payload(GenerationBenchmarkEvidenceSubmission submission,
                                                       String reportSha256) {
        return new GenerationBenchmarkEvidencePayload(
                submission.signatureVersion(),
                submission.subjectType(),
                requireText(submission.subjectKey(), 128, "subjectKey"),
                requireSha256(submission.candidateFingerprint(), "candidateFingerprint"),
                submission.candidatePhysicalRequestCount(),
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

    private void requireCurrentExecutionAttestation(GenerationBenchmarkEvidencePayload payload) {
        if (!GenerationBenchmarkEvidenceProtocol.hasCurrentAttestation(
                payload.signatureVersion(),
                payload.subjectType(),
                payload.candidatePhysicalRequestCount())) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Benchmark 证据签名协议或候选执行证明无效"
            );
        }
    }

    /** 校验{@code ate}{@code Environment}{@code And}时间是否有效。 */
    private void validateEnvironmentAndTime(GenerationBenchmarkEvidencePayload payload, Instant now) {
        if (!payload.datasetFingerprint().equals(datasetFingerprintService.currentFingerprint())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Benchmark 证据数据集指纹已过期");
        }
        if (!payload.graderFingerprint().equals(properties.getGraderFingerprint())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Benchmark 证据评分器指纹已过期");
        }
        if (payload.evaluatedAt() == null || payload.expiresAt() == null
                || payload.evaluatedAt().isAfter(now.plus(MAX_CLOCK_SKEW))
                || !payload.expiresAt().isAfter(now)
                || payload.expiresAt().isAfter(payload.evaluatedAt().plus(properties.getMaximumValidity()))) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Benchmark 证据有效时间窗口无效");
        }
    }

    private String requireReport(String reportJson) {
        if (reportJson == null || reportJson.isBlank()
                || reportJson.getBytes(StandardCharsets.UTF_8).length > properties.getMaximumReportBytes()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "Benchmark 证据报告为空或超过大小上限");
        }
        return reportJson;
    }

    private String requireSha256(String value, String field) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, field + " 必须是 SHA-256");
        }
        return normalized;
    }

    private String requireGitCommit(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!GenerationReleaseProvenanceManifest.isFullGitCommit(normalized)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "gitCommit 必须是完整提交哈希");
        }
        return normalized;
    }

    private String requireText(String value, int maximumLength, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > maximumLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, field + " 无效");
        }
        return normalized;
    }

    /** 校验并返回有效的证据编号。 */
    private String requireEvidenceId(String evidenceId) {
        try {
            return UUID.fromString(evidenceId == null ? "" : evidenceId.trim()).toString();
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "evidenceId 无效", invalid);
        }
    }
}
