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
    /** 创建生成基准测试发布评估实例并完成必要的依赖和初始状态设置。 */
    public GenerationBenchmarkReleaseAssessment {
        violations = violations == null ? List.of() : List.copyOf(violations);
        if (report == null) {
            throw new IllegalArgumentException("benchmark report cannot be null");
        }
    }
}
