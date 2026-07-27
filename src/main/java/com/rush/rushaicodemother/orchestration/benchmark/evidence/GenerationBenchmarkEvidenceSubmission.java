package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import java.time.Instant;

/** 独立评测进程或 CI 发布流水线提交的签名证据。 */
public record GenerationBenchmarkEvidenceSubmission(
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
        String reportJson,
        Instant evaluatedAt,
        Instant expiresAt,
        String signature
) {
}
