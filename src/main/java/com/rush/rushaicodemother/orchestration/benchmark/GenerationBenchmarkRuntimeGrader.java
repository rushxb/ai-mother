package com.rush.rushaicodemother.orchestration.benchmark;

import java.util.List;

/** Extension point for managed runtime graders that may emit multiple quality dimensions. */
public interface GenerationBenchmarkRuntimeGrader {

    String id();

    List<GenerationBenchmarkQualityDimension> dimensions();

    boolean supports(GenerationBenchmarkTask task);

    default int order() {
        return 100;
    }

    List<GenerationBenchmarkRuleResult> evaluate(GenerationBenchmarkRuntimeContext context);
}
