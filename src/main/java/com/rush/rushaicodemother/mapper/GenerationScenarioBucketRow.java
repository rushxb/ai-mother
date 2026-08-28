package com.rush.rushaicodemother.mapper;

/** MyBatis 场景聚合行；基础设施适配器负责组装为领域质量证据。 */
public record GenerationScenarioBucketRow(
        String intentSignature,
        String profileVersion,
        String decisionVersion,
        String route,
        String releaseIdentity,
        Long taskCount,
        Long successCount,
        Long validationRequiredCount,
        Long validationObservedCount,
        Long firstBuildPassCount,
        Long repairObservedCount,
        Long totalRepairRounds,
        Long feedbackCount,
        Long lowRatingCount,
        Double averageRating,
        Long firstUsefulObservedCount,
        Double averageFirstUsefulMs,
        Long p95FirstUsefulMs,
        Long deliveredObservedCount,
        Double averageDeliveredMs,
        Long p95DeliveredMs,
        Long providerCostObservedCount,
        Long totalProviderTokens,
        Long creditCostObservedCount,
        Long totalCreditCost,
        Long capacityObservedTaskCount,
        Long totalPhysicalModelCalls,
        Long maximumPhysicalModelCallsPerTask,
        Long capacityFailureCount
) {
}
