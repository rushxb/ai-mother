package com.rush.rushaicodemother.orchestration.benchmark;

import java.util.List;

/** 托管运行时评分器的扩展点可能会发出多个质量维度。 */
public interface GenerationBenchmarkRuntimeGrader {

    String id();

    List<GenerationBenchmarkQualityDimension> dimensions();

    boolean supports(GenerationBenchmarkTask task);

    default int order() {
        return 100;
    }

    List<GenerationBenchmarkRuleResult> evaluate(GenerationBenchmarkRuntimeContext context);
}
