package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioBucketSummary;
import com.rush.rushaicodemother.orchestration.learning.GenerationStrategyPromotionAssessment;

import java.util.List;

/** 管理端策略晋级评估视图，保留判定所需证据而不暴露持久化行结构。 */
public record GenerationStrategyPromotionAssessmentVO(
        boolean passed,
        List<String> violations,
        String rollbackReleaseIdentity,
        StrategyEvidenceVO baseline,
        StrategyEvidenceVO candidate
) {

    public GenerationStrategyPromotionAssessmentVO {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public static GenerationStrategyPromotionAssessmentVO from(
            GenerationStrategyPromotionAssessment assessment) {
        if (assessment == null) {
            throw new IllegalArgumentException("策略晋级评估不能为空");
        }
        return new GenerationStrategyPromotionAssessmentVO(
                assessment.passed(),
                assessment.violations(),
                assessment.rollbackReleaseIdentity(),
                StrategyEvidenceVO.from(assessment.baseline()),
                StrategyEvidenceVO.from(assessment.candidate()));
    }

    /** 单个真实发布指纹在指定场景和时间窗口内的可审计证据。 */
    public record StrategyEvidenceVO(
            String intentSignature,
            String profileVersion,
            String decisionVersion,
            String route,
            String releaseIdentity,
            QualityEvidenceVO quality,
            LatencyEvidenceVO latency,
            CostEvidenceVO cost,
            CapacityEvidenceVO capacity
    ) {

        private static StrategyEvidenceVO from(GenerationScenarioBucketSummary summary) {
            var identity = summary.identity();
            var quality = summary.quality();
            var economics = summary.deliveryEconomics();
            long taskCount = quality.taskCount();
            return new StrategyEvidenceVO(
                    identity.intentSignature(),
                    identity.profileVersion(),
                    identity.decisionVersion(),
                    identity.route(),
                    identity.releaseIdentity(),
                    new QualityEvidenceVO(
                            taskCount,
                            quality.successCount(),
                            quality.successRate(),
                            quality.validationRequiredCount(),
                            quality.validationObservedCount(),
                            quality.validationObservationRate(),
                            quality.firstBuildPassRate(),
                            quality.repairObservedCount(),
                            observationRate(quality.repairObservedCount(), taskCount),
                            quality.averageRepairRounds(),
                            quality.feedbackCount(),
                            quality.averageRating(),
                            quality.lowRatingRate()),
                    new LatencyEvidenceVO(
                            summary.latency().firstUsefulObservedCount(),
                            summary.latency().firstUsefulObservationRate(taskCount),
                            summary.latency().averageFirstUsefulMs(),
                            summary.latency().p95FirstUsefulMs(),
                            summary.latency().deliveredObservedCount(),
                            summary.latency().deliveryObservationRate(taskCount),
                            summary.latency().averageDeliveredMs(),
                            summary.latency().p95DeliveredMs()),
                    new CostEvidenceVO(
                            summary.cost().providerCostObservedCount(),
                            observationRate(summary.cost().providerCostObservedCount(), taskCount),
                            summary.cost().totalProviderTokens(),
                            summary.cost().averageProviderTokens(taskCount),
                            economics.providerTokensPerSuccessfulDelivery(),
                            summary.cost().creditCostObservedCount(),
                            observationRate(summary.cost().creditCostObservedCount(), taskCount),
                            summary.cost().totalCreditCost(),
                            summary.cost().averageCreditCost(taskCount),
                            economics.creditCostPerSuccessfulDelivery()),
                    new CapacityEvidenceVO(
                            summary.capacity().observedTaskCount(),
                            observationRate(summary.capacity().observedTaskCount(), taskCount),
                            summary.capacity().totalPhysicalModelCalls(),
                            summary.capacity().maximumPhysicalModelCallsPerTask(),
                            summary.capacity().physicalModelCallsPerSuccessfulDelivery(
                                    quality.successCount()),
                            summary.capacity().capacityFailureCount(),
                            summary.capacity().capacityFailureRate(taskCount)));
        }
    }

    public record QualityEvidenceVO(
            long taskCount,
            long successCount,
            double successRate,
            long validationRequiredCount,
            long validationObservedCount,
            double validationObservationRate,
            double firstBuildPassRate,
            long repairObservedCount,
            double repairObservationRate,
            double averageRepairRounds,
            long feedbackCount,
            Double averageRating,
            double lowRatingRate
    ) {
    }

    public record LatencyEvidenceVO(
            long firstUsefulObservedCount,
            double firstUsefulObservationRate,
            Double averageFirstUsefulMs,
            Long p95FirstUsefulMs,
            long deliveredObservedCount,
            double deliveryObservationRate,
            Double averageDeliveredMs,
            Long p95DeliveredMs
    ) {
    }

    /**
     * 成本证据同时保留每次尝试平均值以兼容既有管理端，并显式暴露晋级门禁使用的
     * 单位成功交付成本。
     */
    public record CostEvidenceVO(
            long providerCostObservedCount,
            double providerCostObservationRate,
            long totalProviderTokens,
            double averageProviderTokens,
            Double providerTokensPerSuccessfulDelivery,
            long creditCostObservedCount,
            double creditCostObservationRate,
            long totalCreditCost,
            double averageCreditCost,
            Double creditCostPerSuccessfulDelivery
    ) {
    }

    /** failover、hedge 和重试实际消耗的物理模型请求容量。 */
    public record CapacityEvidenceVO(
            long observedTaskCount,
            double observationRate,
            long totalPhysicalModelCalls,
            long maximumPhysicalModelCallsPerTask,
            Double physicalModelCallsPerSuccessfulDelivery,
            long capacityFailureCount,
            double capacityFailureRate
    ) {
    }

    private static double observationRate(long observedCount, long taskCount) {
        return taskCount == 0 ? 0.0 : (double) observedCount / taskCount;
    }
}
