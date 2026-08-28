package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.config.GenerationBenchmarkEvidenceProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReleaseAssessment;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReleaseGate;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReportValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 发布时重放全部信任校验，拒绝漂移或被存储篡改的证据。 */
@Component
@RequiredArgsConstructor
public class GenerationReleaseEvidenceVerifier {

    private final GenerationBenchmarkEvidenceRepository repository;
    private final GenerationBenchmarkEvidenceCodec codec;
    private final GenerationBenchmarkEvidenceSignatureService signatureService;
    private final GenerationBenchmarkDatasetFingerprintService datasetFingerprintService;
    private final GenerationBenchmarkReportValidator reportValidator;
    private final GenerationBenchmarkReleaseGate releaseGate;
    private final GenerationBenchmarkEvidenceProvenanceValidator provenanceValidator;
    private final GenerationBenchmarkEvidenceCandidateIdentityResolver candidateIdentityResolver;
    private final GenerationBenchmarkEvidenceProperties properties;
    private final Clock clock = Clock.systemUTC();

    /**
 * 校验并返回有效的{@code Passed}。
 *
 * @param evidenceId 证据编号
 * @param candidate 候选
 * @return {@code Passed}
 */
    public GenerationBenchmarkEvidenceRecord requirePassed(
            String evidenceId,
            GenerationBenchmarkEvidenceCandidate candidate) {
        String normalizedId = requireEvidenceId(evidenceId);
        GenerationBenchmarkEvidenceRecord evidence = findEvidence(normalizedId);
        GenerationBenchmarkEvidenceCandidateIdentity expected =
                candidateIdentityResolver.resolve(candidate);
        GenerationBenchmarkEvidencePayload payload = evidence.payload();
        if (payload == null
                || !GenerationBenchmarkEvidenceProtocol.hasCurrentAttestation(
                payload.signatureVersion(),
                payload.subjectType(),
                payload.candidatePhysicalRequestCount())
                || payload.subjectType() != expected.subjectType()
                || !Objects.equals(payload.subjectKey(), expected.subjectKey())
                || !Objects.equals(payload.candidateFingerprint(), expected.candidateFingerprint())
                || !Objects.equals(payload.modelFingerprint(), expected.modelFingerprint())
                || !Objects.equals(
                        payload.promptBundleFingerprint(), expected.promptBundleFingerprint())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Benchmark 证据与发布候选不匹配");
        }
        return verifyPassed(evidence).evidence();
    }

    /**
     * 重放候选无关的完整信任链，供策略归因等需要复用同一份签名报告的控制面使用。
     * 具体候选身份仍须由消费方依据其领域发布身份再次匹配。
     */
    public GenerationVerifiedBenchmarkEvidence requirePassed(String evidenceId) {
        return verifyPassed(findEvidence(requireEvidenceId(evidenceId)));
    }

    private GenerationVerifiedBenchmarkEvidence verifyPassed(
            GenerationBenchmarkEvidenceRecord evidence) {
        GenerationBenchmarkEvidencePayload payload = evidence.payload();
        if (payload == null
                || !GenerationBenchmarkEvidenceProtocol.hasCurrentAttestation(
                payload.signatureVersion(),
                payload.subjectType(),
                payload.candidatePhysicalRequestCount())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Benchmark 证据协议不受支持");
        }
        Instant now = clock.instant();
        if (!payload.expiresAt().isAfter(now)
                || !payload.datasetFingerprint().equals(datasetFingerprintService.currentFingerprint())
                || !payload.graderFingerprint().equals(properties.getGraderFingerprint())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Benchmark 发布证据已过期");
        }
        if (!codec.reportSha256(evidence.reportJson()).equals(payload.reportSha256())
                || !signatureService.verify(payload, evidence.signature())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                    "Benchmark 发布证据完整性校验失败");
        }
        GenerationBenchmarkReport report = codec.parseReport(evidence.reportJson());
        reportValidator.validate(report);
        provenanceValidator.validate(payload, report);
        GenerationBenchmarkReleaseAssessment assessment = releaseGate.assess(report);
        if (!assessment.passed()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Benchmark 发布门禁未通过：" + String.join(",", assessment.violations()));
        }
        return new GenerationVerifiedBenchmarkEvidence(evidence, report);
    }

    private GenerationBenchmarkEvidenceRecord findEvidence(String evidenceId) {
        return repository.findByEvidenceId(evidenceId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR, "Benchmark 发布证据不存在"));
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
