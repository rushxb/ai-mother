package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.orchestration.economics.GenerationDeliveryEconomics;

import java.util.Objects;

/** 按真实策略身份聚合的质量、尾延迟与成本证据。 */
public record GenerationScenarioBucketSummary(
        GenerationScenarioBucketIdentity identity,
        GenerationScenarioQualityMetrics quality,
        GenerationScenarioLatencyMetrics latency,
        GenerationScenarioCostMetrics cost,
        GenerationScenarioCapacityMetrics capacity
) {

    public GenerationScenarioBucketSummary {
        Objects.requireNonNull(identity, "场景策略身份不能为空");
        Objects.requireNonNull(quality, "质量观测不能为空");
        Objects.requireNonNull(latency, "延迟观测不能为空");
        Objects.requireNonNull(cost, "成本观测不能为空");
        Objects.requireNonNull(capacity, "容量观测不能为空");
        if (latency.firstUsefulObservedCount() > quality.taskCount()
                || latency.deliveredObservedCount() > quality.taskCount()
                || cost.providerCostObservedCount() > quality.taskCount()
                || cost.creditCostObservedCount() > quality.taskCount()
                || capacity.observedTaskCount() > quality.taskCount()
                || capacity.capacityFailureCount() > quality.taskCount()) {
            throw new IllegalArgumentException("观测数不能超过任务数");
        }
    }

    /** 将质量成功事实与成本汇总组合成统一的单位成功交付经济性。 */
    public GenerationDeliveryEconomics deliveryEconomics() {
        return cost.deliveryEconomics(quality.successCount());
    }
}
