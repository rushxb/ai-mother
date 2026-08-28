package com.rush.rushaicodemother.orchestration.learning;

/**
 * 策略发布在真实任务中占用的物理模型请求容量。
 *
 * <p>物理调用数来自调用前先写入的模型账本，能够把 failover 与 hedge 的额外请求纳入
 * 晋级判断；未收敛的 STARTED 调用会降低观测完整度，不能被当成零成本。</p>
 */
public record GenerationScenarioCapacityMetrics(
        long observedTaskCount,
        long totalPhysicalModelCalls,
        long maximumPhysicalModelCallsPerTask,
        long capacityFailureCount
) {

    public GenerationScenarioCapacityMetrics {
        if (observedTaskCount < 0 || totalPhysicalModelCalls < 0
                || maximumPhysicalModelCallsPerTask < 0 || capacityFailureCount < 0) {
            throw new IllegalArgumentException("场景容量指标不能为负数");
        }
        if (maximumPhysicalModelCallsPerTask > totalPhysicalModelCalls) {
            throw new IllegalArgumentException("单任务物理调用峰值不能超过调用总数");
        }
    }

    /** 全部物理调用按成功交付数归一，避免失败或对冲请求被摊薄。 */
    public Double physicalModelCallsPerSuccessfulDelivery(long successfulDeliveryCount) {
        if (successfulDeliveryCount <= 0) {
            return null;
        }
        return (double) totalPhysicalModelCalls / successfulDeliveryCount;
    }

    public double capacityFailureRate(long taskCount) {
        return taskCount == 0 ? 0.0 : (double) capacityFailureCount / taskCount;
    }
}
