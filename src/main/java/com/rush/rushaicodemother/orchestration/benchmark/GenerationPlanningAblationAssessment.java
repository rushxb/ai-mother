package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;

import java.util.List;

/** 更精简规划方案相对基线的发布评估。 */
public record GenerationPlanningAblationAssessment(
        boolean passed,
        GenerationPlanningVariant candidate,
        GenerationPlanningVariant baseline,
        List<String> violations
) {
    public GenerationPlanningAblationAssessment {
        if (candidate == null || baseline == null) {
            throw new IllegalArgumentException("规划消融候选和基线不能为空");
        }
        violations = violations == null ? List.of() : List.copyOf(violations);
    }
}
