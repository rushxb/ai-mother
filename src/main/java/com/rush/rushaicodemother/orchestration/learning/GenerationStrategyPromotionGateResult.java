package com.rush.rushaicodemother.orchestration.learning;

import java.util.List;
import java.util.Objects;

/** 仅由线上质量、延迟、成本与容量事实产生的策略门禁结果。 */
public record GenerationStrategyPromotionGateResult(
        boolean passed,
        List<String> violations,
        GenerationScenarioBucketSummary baseline,
        GenerationScenarioBucketSummary candidate
) {

    public GenerationStrategyPromotionGateResult {
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
