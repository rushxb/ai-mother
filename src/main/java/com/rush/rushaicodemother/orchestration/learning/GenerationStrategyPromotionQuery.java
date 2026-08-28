package com.rush.rushaicodemother.orchestration.learning;

import java.time.Instant;
import java.util.UUID;

/** 选择同一观测窗口内的生产基线和候选发布指纹。 */
public record GenerationStrategyPromotionQuery(
        String intentSignature,
        String baselineReleaseIdentity,
        String candidateReleaseIdentity,
        String benchmarkEvidenceId,
        Instant from,
        Instant to
) {

    public GenerationStrategyPromotionQuery {
        if (intentSignature == null || !intentSignature.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("场景签名必须是 SHA-256");
        }
        if (!isSha256(baselineReleaseIdentity) || !isSha256(candidateReleaseIdentity)) {
            throw new IllegalArgumentException("策略发布身份必须是运行时 SHA-256 指纹");
        }
        try {
            benchmarkEvidenceId = UUID.fromString(
                    benchmarkEvidenceId == null ? "" : benchmarkEvidenceId.trim()).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Benchmark 证据编号无效", invalid);
        }
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException("策略观测时间窗口无效");
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
