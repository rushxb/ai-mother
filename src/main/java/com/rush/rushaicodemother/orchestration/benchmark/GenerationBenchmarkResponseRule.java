package com.rush.rushaicodemother.orchestration.benchmark;

/** 对不以发布工作区为唯一结果的 Benchmark 响应执行确定性评分。 */
public interface GenerationBenchmarkResponseRule {

    String id();

    GenerationBenchmarkQualityDimension dimension();

    boolean supports(GenerationBenchmarkTask task);

    default int order() {
        return 100;
    }

    GenerationBenchmarkRuleResult evaluate(GenerationBenchmarkTask task, String responseText);
}
