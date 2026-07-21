package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import java.time.Instant;

/** Signed evidence submitted by an isolated benchmark evaluator or CI release pipeline. */
public record GenerationBenchmarkEvidenceSubmission(
        GenerationBenchmarkEvidenceSubject subjectType,
        String subjectKey,
        String candidateFingerprint,
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
