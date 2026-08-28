package com.rush.rushaicodemother.service.prompt.canary;

import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioBucketSummary;

import java.util.Objects;

/** 同一 Prompt 发布包内稳定组与灰度组的生产事实。 */
public record PromptCanaryObservation(
        GenerationScenarioBucketSummary stable,
        GenerationScenarioBucketSummary canary,
        long ambiguousTaskCount,
        long invalidAttributionTaskCount
) {

    public PromptCanaryObservation {
        Objects.requireNonNull(stable, "Prompt 稳定组观测不能为空");
        Objects.requireNonNull(canary, "Prompt 灰度组观测不能为空");
        if (ambiguousTaskCount < 0 || invalidAttributionTaskCount < 0) {
            throw new IllegalArgumentException("Prompt 灰度排除任务数不能为负数");
        }
    }
}
