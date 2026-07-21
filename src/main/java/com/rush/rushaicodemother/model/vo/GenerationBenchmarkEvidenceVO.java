package com.rush.rushaicodemother.model.vo;

import java.time.Instant;
import java.util.List;

public record GenerationBenchmarkEvidenceVO(
        String evidenceId,
        String subjectType,
        String subjectKey,
        String candidateFingerprint,
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
