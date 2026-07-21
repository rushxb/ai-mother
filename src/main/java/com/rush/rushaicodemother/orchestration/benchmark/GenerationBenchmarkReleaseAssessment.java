package com.rush.rushaicodemother.orchestration.benchmark;

import java.util.List;

public record GenerationBenchmarkReleaseAssessment(
        boolean passed,
        List<String> violations,
        GenerationBenchmarkReport report
) {
    public GenerationBenchmarkReleaseAssessment {
        violations = violations == null ? List.of() : List.copyOf(violations);
        if (report == null) {
            throw new IllegalArgumentException("benchmark report cannot be null");
        }
    }
}
