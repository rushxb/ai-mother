package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.config.GenerationBenchmarkEvidenceProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 独立评测进程与发布控制面之间的 HMAC 信任边界。 */
@Component
@RequiredArgsConstructor
public class GenerationBenchmarkEvidenceSignatureService {

    private final GenerationBenchmarkEvidenceProperties properties;

    /**
 * 返回{@code sign}。
 *
 * @param payload 载荷
 * @return 处理后的生成基准测试证据签名文本
 */
    public String sign(GenerationBenchmarkEvidencePayload payload) {
        if (payload == null || !GenerationBenchmarkEvidenceProtocol.hasCurrentAttestation(
                payload.signatureVersion(),
                payload.subjectType(),
                payload.candidatePhysicalRequestCount())) {
            throw new IllegalArgumentException("Benchmark 证据必须使用当前签名协议和有效执行证明");
        }
        return calculate(payload);
    }

    /** 计算生成基准测试证据签名。 */
    private String calculate(GenerationBenchmarkEvidencePayload payload) {
        String secret = properties.getSigningSecret();
        if (secret == null || secret.length() < 32) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Benchmark 证据签名密钥未配置"
            );
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Benchmark 证据签名算法不可用", failure);
        }
    }

    /**
 * 验证生成基准测试证据签名是否符合预期。
 *
 * @param payload 载荷
 * @param signature 签名
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean verify(GenerationBenchmarkEvidencePayload payload, String signature) {
        if (signature == null || !signature.matches("[0-9a-fA-F]{64}")
                || payload == null
                || !GenerationBenchmarkEvidenceProtocol.hasValidAttestation(
                payload.signatureVersion(),
                payload.subjectType(),
                payload.candidatePhysicalRequestCount())) {
            return false;
        }
        byte[] expected = HexFormat.of().parseHex(calculate(payload));
        byte[] supplied = HexFormat.of().parseHex(signature);
        return MessageDigest.isEqual(expected, supplied);
    }

    /** 判断当前状态是否允许{@code onical}。 */
    String canonical(GenerationBenchmarkEvidencePayload payload) {
        if (payload == null || payload.subjectType() == null
                || payload.evaluatedAt() == null || payload.expiresAt() == null
                || !GenerationBenchmarkEvidenceProtocol.hasValidAttestation(
                payload.signatureVersion(),
                payload.subjectType(),
                payload.candidatePhysicalRequestCount())) {
            throw new IllegalArgumentException("Benchmark 证据签名载荷不完整");
        }
        StringBuilder canonical = new StringBuilder(
                "generation-benchmark-evidence-v" + payload.signatureVersion() + "|");
        ReleaseCandidateFingerprint.appendField(canonical, payload.subjectType().name());
        ReleaseCandidateFingerprint.appendField(canonical, payload.subjectKey());
        ReleaseCandidateFingerprint.appendField(canonical, payload.candidateFingerprint());
        if (payload.signatureVersion()
                >= GenerationBenchmarkEvidenceProtocol.CURRENT_SIGNATURE_VERSION) {
            ReleaseCandidateFingerprint.appendField(
                    canonical, Long.toString(payload.candidatePhysicalRequestCount()));
        }
        ReleaseCandidateFingerprint.appendField(canonical, payload.datasetFingerprint());
        ReleaseCandidateFingerprint.appendField(canonical, payload.graderFingerprint());
        ReleaseCandidateFingerprint.appendField(canonical, payload.runtimeConfigFingerprint());
        ReleaseCandidateFingerprint.appendField(canonical, payload.gitCommit());
        ReleaseCandidateFingerprint.appendField(canonical, payload.modelFingerprint());
        ReleaseCandidateFingerprint.appendField(canonical, payload.promptBundleFingerprint());
        ReleaseCandidateFingerprint.appendField(canonical, payload.reportSha256());
        ReleaseCandidateFingerprint.appendField(canonical, payload.evaluatedAt().toString());
        ReleaseCandidateFingerprint.appendField(canonical, payload.expiresAt().toString());
        return canonical.toString();
    }
}
