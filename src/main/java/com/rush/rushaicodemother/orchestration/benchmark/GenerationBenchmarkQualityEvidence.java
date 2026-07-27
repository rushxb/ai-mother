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

    public boolean evaluated(GenerationBenchmarkQualityDimension dimension) {
        return ruleResults.stream().anyMatch(result -> result.dimension() == dimension);
    }

    public boolean passed(GenerationBenchmarkQualityDimension dimension) {
        List<GenerationBenchmarkRuleResult> matching = ruleResults.stream()
                .filter(result -> result.dimension() == dimension)
                .toList();
        return !matching.isEmpty() && matching.stream().allMatch(GenerationBenchmarkRuleResult::passed);
    }

    public boolean overallPassed() {
        return !ruleResults.isEmpty() && ruleResults.stream().allMatch(GenerationBenchmarkRuleResult::passed);
    }

    public int changedFileCount() {
        return ruleResults.stream()
                .mapToInt(GenerationBenchmarkRuleResult::changedFileCount)
                .max()
                .orElse(0);
    }

    public List<String> violations() {
        return ruleResults.stream()
                .flatMap(result -> result.violations().stream()
                        .map(violation -> result.ruleId() + ":" + violation))
                .toList();
    }
}
