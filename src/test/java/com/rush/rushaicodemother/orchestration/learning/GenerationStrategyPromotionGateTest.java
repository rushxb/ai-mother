package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationStrategyPromotionGateTest {

    private static final String INTENT_SIGNATURE = "a".repeat(64);
    private static final String BASELINE_RELEASE = "b".repeat(64);
    private static final String CANDIDATE_RELEASE = "c".repeat(64);

    @Test
    void higherSuccessRateMustNotHideDeliveredP95Regression() {
        GenerationScenarioBucketSummary baseline = summary(
                BASELINE_RELEASE, 40, 38, 36, 2, 4.4, 4,
                4_000L, 12_000L, 400_000L, 200L);
        GenerationScenarioBucketSummary candidate = summary(
                CANDIDATE_RELEASE, 40, 39, 37, 1, 4.6, 2,
                3_500L, 12_001L, 390_000L, 190L);

        GenerationStrategyPromotionAssessment assessment = gate().assess(baseline, candidate);

        assertFalse(assessment.passed());
        assertTrue(assessment.violations().contains("delivered_p95_regressed"));
        assertEquals(BASELINE_RELEASE, assessment.rollbackReleaseIdentity());
    }

    @Test
    void insufficientSamplesOrIncompleteObservationsMustBlockPromotion() {
        GenerationScenarioBucketSummary baseline = summary(
                BASELINE_RELEASE, 40, 38, 36, 2, 4.4, 4,
                4_000L, 12_000L, 400_000L, 200L);
        GenerationScenarioBucketSummary candidate = new GenerationScenarioBucketSummary(
                identity(CANDIDATE_RELEASE),
                new GenerationScenarioQualityMetrics(31, 31, 31, 30, 30, 30, 0, 5, 0, 4.8),
                new GenerationScenarioLatencyMetrics(30, 2_000.0, 3_000L, 30, 8_000.0, 10_000L),
                new GenerationScenarioCostMetrics(30, 200_000L, 30, 100L));

        GenerationStrategyPromotionAssessment assessment = gate().assess(baseline, candidate);

        assertFalse(assessment.passed());
        assertTrue(assessment.violations().contains("candidate_task_count_below_minimum"));
        assertTrue(assessment.violations().contains("candidate_validation_observation_incomplete"));
        assertTrue(assessment.violations().contains("candidate_repair_observation_incomplete"));
        assertTrue(assessment.violations().contains("candidate_first_useful_observation_incomplete"));
        assertTrue(assessment.violations().contains("candidate_delivery_observation_incomplete"));
        assertTrue(assessment.violations().contains("candidate_provider_cost_observation_incomplete"));
        assertTrue(assessment.violations().contains("candidate_credit_cost_observation_incomplete"));
    }

    @Test
    void qualityAndProviderCostRegressionsMustBlockPromotionWithinOneAssessment() {
        GenerationScenarioBucketSummary baseline = summary(
                BASELINE_RELEASE, 40, 39, 38, 1, 4.7, 1,
                4_000L, 12_000L, 400_000L, 200L);
        GenerationScenarioBucketSummary candidate = summary(
                CANDIDATE_RELEASE, 40, 38, 35, 3, 4.1, 8,
                3_000L, 11_000L, 410_000L, 220L);

        GenerationStrategyPromotionAssessment assessment = gate().assess(baseline, candidate);

        assertFalse(assessment.passed());
        assertTrue(assessment.violations().contains("success_rate_regressed"));
        assertTrue(assessment.violations().contains("first_build_pass_rate_regressed"));
        assertTrue(assessment.violations().contains("average_rating_regressed"));
        assertTrue(assessment.violations().contains("low_rating_rate_regressed"));
        assertTrue(assessment.violations().contains("repair_rounds_regressed"));
        assertTrue(assessment.violations().contains("average_provider_tokens_regressed"));
        assertTrue(assessment.violations().contains("average_credit_cost_regressed"));
    }

    @Test
    void completeNonRegressingEvidenceWithAnObservedImprovementMustPass() {
        GenerationScenarioBucketSummary baseline = summary(
                BASELINE_RELEASE, 40, 38, 36, 2, 4.4, 4,
                4_000L, 12_000L, 400_000L, 200L);
        GenerationScenarioBucketSummary candidate = summary(
                CANDIDATE_RELEASE, 40, 39, 37, 1, 4.6, 2,
                3_500L, 11_000L, 390_000L, 190L);

        GenerationStrategyPromotionAssessment assessment = gate().assess(baseline, candidate);

        assertTrue(assessment.passed());
        assertTrue(assessment.violations().isEmpty());
        assertEquals(BASELINE_RELEASE, assessment.rollbackReleaseIdentity());
        assertEquals(CANDIDATE_RELEASE, assessment.candidateReleaseIdentity());
    }

    private GenerationStrategyPromotionGate gate() {
        return new GenerationStrategyPromotionGate(new GenerationBenchmarkReleaseProperties());
    }

    private GenerationScenarioBucketSummary summary(String releaseIdentity,
                                                      long taskCount,
                                                      long successCount,
                                                      long firstBuildPassCount,
                                                      long lowRatingCount,
                                                      double averageRating,
                                                      long repairRounds,
                                                      long p95FirstUsefulMs,
                                                      long p95DeliveredMs,
                                                      long totalProviderTokens,
                                                      long totalCreditCost) {
        return new GenerationScenarioBucketSummary(
                identity(releaseIdentity),
                new GenerationScenarioQualityMetrics(
                        taskCount, successCount, taskCount, taskCount, firstBuildPassCount,
                        taskCount, repairRounds, 10, lowRatingCount, averageRating),
                new GenerationScenarioLatencyMetrics(
                        taskCount, (double) p95FirstUsefulMs / 2, p95FirstUsefulMs,
                        taskCount, (double) p95DeliveredMs / 2, p95DeliveredMs),
                new GenerationScenarioCostMetrics(
                        taskCount, totalProviderTokens, taskCount, totalCreditCost));
    }

    private GenerationScenarioBucketIdentity identity(String releaseIdentity) {
        return new GenerationScenarioBucketIdentity(
                INTENT_SIGNATURE,
                "intent-profile-v1",
                "routing-policy-v1",
                "agent_edit",
                releaseIdentity);
    }
}
