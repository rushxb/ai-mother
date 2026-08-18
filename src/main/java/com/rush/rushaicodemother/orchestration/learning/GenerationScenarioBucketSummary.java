package com.rush.rushaicodemother.orchestration.learning;

import java.util.Objects;

/** 按真实策略身份聚合的质量、尾延迟与成本证据。 */
public record GenerationScenarioBucketSummary(
        GenerationScenarioBucketIdentity identity,
        GenerationScenarioQualityMetrics quality,
        GenerationScenarioLatencyMetrics latency,
        GenerationScenarioCostMetrics cost
) {

    public GenerationScenarioBucketSummary {
        Objects.requireNonNull(identity, "场景策略身份不能为空");
        Objects.requireNonNull(quality, "质量观测不能为空");
        Objects.requireNonNull(latency, "延迟观测不能为空");
        Objects.requireNonNull(cost, "成本观测不能为空");
        if (latency.firstUsefulObservedCount() > quality.taskCount()
                || latency.deliveredObservedCount() > quality.taskCount()
                || cost.providerCostObservedCount() > quality.taskCount()
                || cost.creditCostObservedCount() > quality.taskCount()) {
            throw new IllegalArgumentException("观测数不能超过任务数");
        }
    }
}
