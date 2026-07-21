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

/** HMAC trust boundary between isolated evaluators and the release control plane. */
@Component
@RequiredArgsConstructor
public class GenerationBenchmarkEvidenceSignatureService {

    private final GenerationBenchmarkEvidenceProperties properties;

    public String sign(GenerationBenchmarkEvidencePayload payload) {
        String secret = properties.getSigningSecret();
        if (secret == null || secret.length() < 32) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Benchmark evidence signing secret is not configured"
            );
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Benchmark evidence signature algorithm is unavailable", failure);
        }
    }

    public boolean verify(GenerationBenchmarkEvidencePayload payload, String signature) {
        if (signature == null || !signature.matches("[0-9a-fA-F]{64}")) {
            return false;
        }
        byte[] expected = HexFormat.of().parseHex(sign(payload));
        byte[] supplied = HexFormat.of().parseHex(signature);
        return MessageDigest.isEqual(expected, supplied);
    }

    String canonical(GenerationBenchmarkEvidencePayload payload) {
        if (payload == null || payload.subjectType() == null
                || payload.evaluatedAt() == null || payload.expiresAt() == null) {
            throw new IllegalArgumentException("benchmark evidence payload is incomplete");
        }
        StringBuilder canonical = new StringBuilder("generation-benchmark-evidence-v1|");
        ReleaseCandidateFingerprint.appendField(canonical, payload.subjectType().name());
        ReleaseCandidateFingerprint.appendField(canonical, payload.subjectKey());
        ReleaseCandidateFingerprint.appendField(canonical, payload.candidateFingerprint());
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
