package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

/** 确定性基准夹具设置和分级的策略扩展点。 */
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
