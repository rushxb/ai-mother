package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import java.time.Instant;

/** 固定字段顺序的签名载荷，报告正文由 reportSha256 纳入完整性保护。 */
public record GenerationBenchmarkEvidencePayload(
        int signatureVersion,
        GenerationBenchmarkEvidenceSubject subjectType,
        String subjectKey,
        String candidateFingerprint,
        long candidatePhysicalRequestCount,
        String datasetFingerprint,
        String graderFingerprint,
        String runtimeConfigFingerprint,
        String gitCommit,
        String modelFingerprint,
        String promptBundleFingerprint,
        String reportSha256,
        Instant evaluatedAt,
        Instant expiresAt
) {
}
