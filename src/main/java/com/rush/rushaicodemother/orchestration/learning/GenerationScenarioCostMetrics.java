package com.rush.rushaicodemother.orchestration.learning;

/** Provider 实际 token 成本与用户收费的独立聚合，晋级时两者分别受控。 */
public record GenerationScenarioCostMetrics(
        long providerCostObservedCount,
        long totalProviderTokens,
        long creditCostObservedCount,
        long totalCreditCost
) {

    public GenerationScenarioCostMetrics {
        if (providerCostObservedCount < 0 || totalProviderTokens < 0
                || creditCostObservedCount < 0 || totalCreditCost < 0) {
            throw new IllegalArgumentException("场景成本不能为负数");
        }
        if (providerCostObservedCount == 0 && totalProviderTokens > 0) {
            throw new IllegalArgumentException("无 Provider 成本观测时 token 成本必须为零");
        }
        if (creditCostObservedCount == 0 && totalCreditCost > 0) {
            throw new IllegalArgumentException("无收费观测时积分成本必须为零");
        }
    }

    public double averageProviderTokens(long taskCount) {
        return average(totalProviderTokens, taskCount);
    }

    public double averageCreditCost(long taskCount) {
        return average(totalCreditCost, taskCount);
    }

    private static double average(long total, long taskCount) {
        return taskCount == 0 ? 0.0 : (double) total / taskCount;
    }
}
