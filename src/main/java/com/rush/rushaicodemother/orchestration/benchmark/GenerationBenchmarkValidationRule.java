package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

/** Strategy extension point for deterministic benchmark fixture setup and grading. */
public interface GenerationBenchmarkValidationRule {

    String id();

    GenerationBenchmarkQualityDimension dimension();

    boolean supports(GenerationBenchmarkTask task);

    default int order() {
        return 100;
    }

    default void prepare(GenerationBenchmarkTask task, GenerationWorkspace workspace) {
    }

    GenerationBenchmarkRuleResult evaluate(
            GenerationBenchmarkTask task,
            GenerationWorkspace workspace,
            GenerationBenchmarkWorkspaceSnapshot baseline
    );
}
