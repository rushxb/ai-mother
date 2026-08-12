package com.rush.rushaicodemother.orchestration.learning;

/** 按场景签名与实际路由聚合的质量/成本摘要。 */
public record GenerationScenarioBucketSummary(
        String intentSignature,
        String profileVersion,
        String decisionVersion,
        String route,
        String releaseIdentity,
        Long taskCount,
        Long successCount,
        Long lowRatingCount,
        Double averageRating,
        Double averageDurationMs,
        Long totalTokens,
        Long totalCreditCost
) {
}
