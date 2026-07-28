package com.rush.rushaicodemother.orchestration.benchmark;

import java.util.List;

/** 附在一次基准测试运行中的可组合质量证据。 */
public record GenerationBenchmarkQualityEvidence(
        List<GenerationBenchmarkRuleResult> ruleResults
) {
    public GenerationBenchmarkQualityEvidence {
        ruleResults = ruleResults == null ? List.of() : List.copyOf(ruleResults);
    }

    public static GenerationBenchmarkQualityEvidence empty() {
        return new GenerationBenchmarkQualityEvidence(List.of());
    }

    /**
 * 返回{@code evaluated}。
 *
 * @param dimension {@code dimension} 对应的调用参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean evaluated(GenerationBenchmarkQualityDimension dimension) {
        return ruleResults.stream().anyMatch(result -> result.dimension() == dimension);
    }

    /**
 * 返回{@code passed}。
 *
 * @param dimension {@code dimension} 对应的调用参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean passed(GenerationBenchmarkQualityDimension dimension) {
        List<GenerationBenchmarkRuleResult> matching = ruleResults.stream()
                .filter(result -> result.dimension() == dimension)
                .toList();
        return !matching.isEmpty() && matching.stream().allMatch(GenerationBenchmarkRuleResult::passed);
    }

    /**
 * 返回{@code overall}{@code Passed}。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean overallPassed() {
        return !ruleResults.isEmpty() && ruleResults.stream().allMatch(GenerationBenchmarkRuleResult::passed);
    }

    /**
 * 返回变更文件数量。
 *
 * @return 计算或处理后的数值结果
 */
    public int changedFileCount() {
        return ruleResults.stream()
                .mapToInt(GenerationBenchmarkRuleResult::changedFileCount)
                .max()
                .orElse(0);
    }

    /**
 * 返回{@code violations}。
 *
 * @return 生成基准测试质量证据集合
 */
    public List<String> violations() {
        return ruleResults.stream()
                .flatMap(result -> result.violations().stream()
                        .map(violation -> result.ruleId() + ":" + violation))
                .toList();
    }
}
