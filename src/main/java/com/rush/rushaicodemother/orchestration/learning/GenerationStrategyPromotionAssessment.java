package com.rush.rushaicodemother.orchestration.learning;

import java.util.List;
import java.util.Objects;

/** 策略晋级结果同时保留候选证据和确定的回滚目标。 */
public record GenerationStrategyPromotionAssessment(
        boolean passed,
        List<String> violations,
        GenerationScenarioBucketSummary baseline,
        GenerationScenarioBucketSummary candidate
) {

    public GenerationStrategyPromotionAssessment {
        violations = violations == null ? List.of() : List.copyOf(violations);
        Objects.requireNonNull(baseline, "基线证据不能为空");
        Objects.requireNonNull(candidate, "候选证据不能为空");
    }

    public String rollbackReleaseIdentity() {
        return baseline.identity().releaseIdentity();
    }

    public String candidateReleaseIdentity() {
        return candidate.identity().releaseIdentity();
    }
}
