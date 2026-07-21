package com.rush.rushaicodemother.orchestration.benchmark;

import java.util.List;

/** One deterministic grader result. Violation values are stable machine-readable codes. */
public record GenerationBenchmarkRuleResult(
        String ruleId,
        GenerationBenchmarkQualityDimension dimension,
        boolean passed,
        List<String> violations,
        int changedFileCount
) {
    public GenerationBenchmarkRuleResult {
        ruleId = ruleId == null || ruleId.isBlank() ? "unknown" : ruleId.trim();
        if (dimension == null) {
            throw new IllegalArgumentException("benchmark quality dimension is required");
        }
        violations = violations == null ? List.of() : List.copyOf(violations);
        changedFileCount = Math.max(0, changedFileCount);
    }

    public static GenerationBenchmarkRuleResult passed(
            String ruleId,
            GenerationBenchmarkQualityDimension dimension
    ) {
        return new GenerationBenchmarkRuleResult(ruleId, dimension, true, List.of(), 0);
    }

    public static GenerationBenchmarkRuleResult failed(
            String ruleId,
            GenerationBenchmarkQualityDimension dimension,
            String violation
    ) {
        return new GenerationBenchmarkRuleResult(
                ruleId, dimension, false, List.of(violation), 0);
    }
}
