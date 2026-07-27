package com.rush.rushaicodemother.model.vo;

import java.time.Instant;
import java.util.List;

/**
 * 生成基准测试证据接口视图对象。
 */
public record GenerationBenchmarkEvidenceVO(
        String evidenceId,
        String subjectType,
        String subjectKey,
        String candidateFingerprint,
        int signatureVersion,
        long candidatePhysicalRequestCount,
        String datasetFingerprint,
        String graderFingerprint,
        String runtimeConfigFingerprint,
        String gitCommit,
        String modelFingerprint,
        String promptBundleFingerprint,
        boolean passed,
        List<String> violations,
        Instant evaluatedAt,
        Instant expiresAt,
        Instant createdAt
) {
}
