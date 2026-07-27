package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.config.GenerationBenchmarkEvidenceProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReportValidator;
import com.rush.rushaicodemother.service.release.AiReleaseCoordinationLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * 在控制面生成规范 Benchmark evidence envelope，避免 Worker 手工填写可信来源字段。
 */
@Service
public class GenerationBenchmarkEvidenceEnvelopeService {

    private static final String SHA256_PATTERN = "[0-9a-f]{64}";
    private static final int MAX_SUBJECT_KEY_LENGTH = 128;

    private final GenerationBenchmarkEvidenceCodec codec;
    private final GenerationBenchmarkReportValidator reportValidator;
    private final GenerationBenchmarkDatasetFingerprintService datasetFingerprintService;
    private final GenerationBenchmarkEvidenceProperties properties;
    private final GenerationReleaseProvenanceProvider releaseProvenanceProvider;
    private final GenerationBenchmarkEvidenceSignatureService signatureService;
    private final GenerationBenchmarkEvidenceCandidateIdentityResolver candidateIdentityResolver;
    private final AiReleaseCoordinationLock coordinationLock;
    private final Clock clock;

    @Autowired
    public GenerationBenchmarkEvidenceEnvelopeService(
            GenerationBenchmarkEvidenceCodec codec,
            GenerationBenchmarkReportValidator reportValidator,
            GenerationBenchmarkDatasetFingerprintService datasetFingerprintService,
            GenerationBenchmarkEvidenceProperties properties,
            GenerationReleaseProvenanceProvider releaseProvenanceProvider,
            GenerationBenchmarkEvidenceSignatureService signatureService,
            GenerationBenchmarkEvidenceCandidateIdentityResolver candidateIdentityResolver,
            AiReleaseCoordinationLock coordinationLock
    ) {
        this(codec, reportValidator, datasetFingerprintService, properties, releaseProvenanceProvider,
                signatureService, candidateIdentityResolver, coordinationLock, Clock.systemUTC());
    }

    GenerationBenchmarkEvidenceEnvelopeService(
            GenerationBenchmarkEvidenceCodec codec,
            GenerationBenchmarkReportValidator reportValidator,
            GenerationBenchmarkDatasetFingerprintService datasetFingerprintService,
            GenerationBenchmarkEvidenceProperties properties,
            GenerationReleaseProvenanceProvider releaseProvenanceProvider,
            GenerationBenchmarkEvidenceSignatureService signatureService,
            GenerationBenchmarkEvidenceCandidateIdentityResolver candidateIdentityResolver,
            AiReleaseCoordinationLock coordinationLock,
            Clock clock
    ) {
        this.codec = codec;
        this.reportValidator = reportValidator;
        this.datasetFingerprintService = datasetFingerprintService;
        this.properties = properties;
        this.releaseProvenanceProvider = releaseProvenanceProvider;
        this.signatureService = signatureService;
        this.candidateIdentityResolver = candidateIdentityResolver;
        this.coordinationLock = coordinationLock;
        this.clock = clock;
    }

    /** 在同一发布协调事务中读取状态并生成签名 envelope。 */
    @Transactional(rollbackFor = Exception.class)
    public GenerationBenchmarkEvidenceSubmission create(
            GenerationBenchmarkEvidenceEnvelopeRequest request) {
        if (request == null || request.candidate() == null || request.report() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Benchmark Worker 请求不完整");
        }
        Duration validity = requireValidity(request.validity());
        requireCandidateExecutionAttestation(
                request.candidate().subjectType(),
                request.candidatePhysicalRequestCount());
        reportValidator.validate(request.report());
        String reportJson = codec.serializeReport(request.report());
        if (reportJson.getBytes(StandardCharsets.UTF_8).length > properties.getMaximumReportBytes()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Benchmark 报告超过允许大小");
        }
        String promptBundleFingerprint = requireSha256(
                request.report().promptBundleId(), "Prompt 版本包指纹");
        String modelFingerprint = requireSha256(
                request.report().modelFingerprint(), "Benchmark 实际模型池指纹");

        coordinationLock.acquire();
        GenerationBenchmarkEvidenceCandidateIdentity candidate =
                candidateIdentityResolver.resolve(request.candidate());
        if (candidate.subjectType() != request.candidate().subjectType()) {
            throw new IllegalStateException("Benchmark 候选解析器返回了错误的候选类型");
        }
        String subjectKey = requireSubjectKey(candidate.subjectKey());
        String candidateFingerprint = requireSha256(
                candidate.candidateFingerprint(), "Benchmark 候选指纹");
        String expectedModelFingerprint = requireSha256(
                candidate.modelFingerprint(), "Benchmark 模型指纹");
        String expectedPromptBundleFingerprint = requireSha256(
                candidate.promptBundleFingerprint(), "Benchmark Prompt 版本包指纹");
        if (!modelFingerprint.equals(expectedModelFingerprint)) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Benchmark 报告实际模型池与发布候选不一致"
            );
        }
        if (!promptBundleFingerprint.equals(expectedPromptBundleFingerprint)) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Benchmark 报告实际 Prompt 版本包与发布候选不一致"
            );
        }
        GenerationReleaseProvenanceManifest provenance = releaseProvenanceProvider.current();
        Instant evaluatedAt = clock.instant();
        Instant expiresAt;
        try {
            expiresAt = evaluatedAt.plus(validity);
        } catch (RuntimeException overflow) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Benchmark 证据有效期无效", overflow);
        }
        GenerationBenchmarkEvidencePayload payload = new GenerationBenchmarkEvidencePayload(
                GenerationBenchmarkEvidenceProtocol.CURRENT_SIGNATURE_VERSION,
                candidate.subjectType(),
                subjectKey,
                candidateFingerprint,
                request.candidatePhysicalRequestCount(),
                datasetFingerprintService.currentFingerprint(),
                requireText(properties.getGraderFingerprint(), "Benchmark 评测器指纹"),
                provenance.runtimeConfigFingerprint(),
                provenance.gitCommit(),
                modelFingerprint,
                promptBundleFingerprint,
                codec.reportSha256(reportJson),
                evaluatedAt,
                expiresAt
        );
        return new GenerationBenchmarkEvidenceSubmission(
                payload.signatureVersion(),
                payload.subjectType(),
                payload.subjectKey(),
                payload.candidateFingerprint(),
                payload.candidatePhysicalRequestCount(),
                payload.datasetFingerprint(),
                payload.graderFingerprint(),
                payload.runtimeConfigFingerprint(),
                payload.gitCommit(),
                payload.modelFingerprint(),
                payload.promptBundleFingerprint(),
                reportJson,
                payload.evaluatedAt(),
                payload.expiresAt(),
                signatureService.sign(payload)
        );
    }

    private void requireCandidateExecutionAttestation(
            GenerationBenchmarkEvidenceSubject subjectType,
            long candidatePhysicalRequestCount) {
        if (!GenerationBenchmarkEvidenceProtocol.hasCurrentAttestation(
                GenerationBenchmarkEvidenceProtocol.CURRENT_SIGNATURE_VERSION,
                subjectType,
                candidatePhysicalRequestCount)) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Benchmark 候选物理请求执行证明无效"
            );
        }
    }

    private Duration requireValidity(Duration validity) {
        if (validity == null || validity.isZero() || validity.isNegative()
                || validity.compareTo(properties.getMaximumValidity()) > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Benchmark 证据有效期超出允许范围");
        }
        return validity;
    }

    private String requireSubjectKey(String value) {
        return requireText(value, "Benchmark 候选标识");
    }

    private String requireSha256(String value, String field) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(SHA256_PATTERN)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, field + "必须是 SHA-256");
        }
        return normalized;
    }

    private String requireText(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > MAX_SUBJECT_KEY_LENGTH
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, field + "无效");
        }
        return normalized;
    }
}
