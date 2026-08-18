package com.rush.rushaicodemother.orchestration.benchmark;

import java.util.List;

/**
 * 生成基准测试发布评估的不可变数据载体。
 */
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
