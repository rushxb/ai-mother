package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import java.time.Instant;

/** Fixed-order signed payload; the report itself is covered by reportSha256. */
public record GenerationBenchmarkEvidencePayload(
        GenerationBenchmarkEvidenceSubject subjectType,
        String subjectKey,
        String candidateFingerprint,
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
