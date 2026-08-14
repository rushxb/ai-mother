package com.rush.rushaicodemother.orchestration.decision;

import java.util.Objects;

/** 场景 preflight 的最终决策与已消耗预算快照。 */
public record GenerationScenarioPreflightResult(
        GenerationScenarioDecision scenarioDecision,
        GenerationPreflightUsage usage
) {

    public GenerationScenarioPreflightResult {
        Objects.requireNonNull(scenarioDecision, "preflight 场景决策不能为空");
        usage = usage == null ? GenerationPreflightUsage.none() : usage;
    }
}
