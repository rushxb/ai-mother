package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 用同一场景的真实生产结果比较 Champion 与候选策略。
 *
 * <p>该门禁复用 Benchmark 的绝对质量和成本预算，并额外要求候选对生产基线无回归。
 * 因此调用方不能只用成功率或一个手写版本号绕过尾延迟、成本与观测完整性。</p>
 */
@Component
public class GenerationStrategyPromotionGate {

    static final int MINIMUM_FEEDBACK_COUNT = 5;

    private final GenerationBenchmarkReleaseProperties releaseProperties;

    public GenerationStrategyPromotionGate(GenerationBenchmarkReleaseProperties releaseProperties) {
        this.releaseProperties = Objects.requireNonNull(releaseProperties, "发布门禁配置不能为空");
    }

    public GenerationStrategyPromotionAssessment assess(GenerationScenarioBucketSummary baseline,
                                                         GenerationScenarioBucketSummary candidate) {
        Objects.requireNonNull(baseline, "基线证据不能为空");
        Objects.requireNonNull(candidate, "候选证据不能为空");
        List<String> violations = new ArrayList<>();
        assessIdentity(baseline.identity(), candidate.identity(), violations);
        assessSamplesAndObservations("baseline", baseline, violations);
        assessSamplesAndObservations("candidate", candidate, violations);
        assessAbsoluteCandidate(candidate, violations);
        assessRelativeQuality(baseline, candidate, violations);
        assessRelativeLatency(baseline, candidate, violations);
        assessRelativeCost(baseline, candidate, violations);
        if (violations.isEmpty() && !hasObservedImprovement(baseline, candidate)) {
            violations.add("candidate_has_no_observed_improvement");
        }
        return new GenerationStrategyPromotionAssessment(
                violations.isEmpty(), violations, baseline, candidate);
    }

    private void assessIdentity(GenerationScenarioBucketIdentity baseline,
                                GenerationScenarioBucketIdentity candidate,
                                List<String> violations) {
        if (!baseline.intentSignature().equals(candidate.intentSignature())) {
            violations.add("intent_signature_mismatch");
        }
        if (!isSha256(baseline.releaseIdentity()) || !isSha256(candidate.releaseIdentity())) {
            violations.add("release_identity_not_runtime_fingerprint");
        } else if (baseline.releaseIdentity().equals(candidate.releaseIdentity())) {
            violations.add("candidate_release_matches_baseline");
        }
    }

    private void assessSamplesAndObservations(String role,
                                              GenerationScenarioBucketSummary summary,
                                              List<String> violations) {
        GenerationScenarioQualityMetrics quality = summary.quality();
        GenerationScenarioLatencyMetrics latency = summary.latency();
        if (quality.taskCount() < releaseProperties.getMinimumTaskCount()) {
            violations.add(role + "_task_count_below_minimum");
        }
        if (quality.feedbackCount() < MINIMUM_FEEDBACK_COUNT) {
            violations.add(role + "_feedback_count_below_minimum");
        }
        if (quality.validationObservationRate() < 1.0) {
            violations.add(role + "_validation_observation_incomplete");
        }
        if (quality.repairObservedCount() < quality.taskCount()) {
            violations.add(role + "_repair_observation_incomplete");
        }
        if (latency.firstUsefulObservationRate(quality.taskCount())
                < releaseProperties.getMinimumFirstPreviewObservationRate()) {
            violations.add(role + "_first_useful_observation_incomplete");
        }
        if (latency.deliveryObservationRate(quality.taskCount()) < 1.0) {
            violations.add(role + "_delivery_observation_incomplete");
        }
        if (summary.cost().providerCostObservedCount() < quality.taskCount()) {
            violations.add(role + "_provider_cost_observation_incomplete");
        }
        if (summary.cost().creditCostObservedCount() < quality.taskCount()) {
            violations.add(role + "_credit_cost_observation_incomplete");
        }
    }

    private void assessAbsoluteCandidate(GenerationScenarioBucketSummary candidate,
                                         List<String> violations) {
        GenerationScenarioQualityMetrics quality = candidate.quality();
        if (quality.successRate() < releaseProperties.getMinimumSuccessRate()) {
            violations.add("candidate_success_rate_below_minimum");
        }
        if (quality.firstBuildPassRate() < releaseProperties.getMinimumBuildPassRate()) {
            violations.add("candidate_first_build_pass_rate_below_minimum");
        }
        double averageTokens = candidate.cost().averageProviderTokens(quality.taskCount());
        if (averageTokens > releaseProperties.getMaximumAverageTokens()) {
            violations.add("candidate_average_provider_tokens_above_budget");
        }
        double averageCredit = candidate.cost().averageCreditCost(quality.taskCount());
        if (averageCredit > releaseProperties.getMaximumAverageCreditCost()) {
            violations.add("candidate_average_credit_cost_above_budget");
        }
    }

    private void assessRelativeQuality(GenerationScenarioBucketSummary baseline,
                                       GenerationScenarioBucketSummary candidate,
                                       List<String> violations) {
        GenerationScenarioQualityMetrics base = baseline.quality();
        GenerationScenarioQualityMetrics next = candidate.quality();
        if (next.successRate() < base.successRate()) {
            violations.add("success_rate_regressed");
        }
        if (next.firstBuildPassRate() < base.firstBuildPassRate()) {
            violations.add("first_build_pass_rate_regressed");
        }
        if (next.averageRepairRounds() > base.averageRepairRounds()) {
            violations.add("repair_rounds_regressed");
        }
        if (base.feedbackCount() >= MINIMUM_FEEDBACK_COUNT
                && next.feedbackCount() >= MINIMUM_FEEDBACK_COUNT) {
            if (next.averageRating() < base.averageRating()) {
                violations.add("average_rating_regressed");
            }
            if (next.lowRatingRate() > base.lowRatingRate()) {
                violations.add("low_rating_rate_regressed");
            }
        }
    }

    private void assessRelativeLatency(GenerationScenarioBucketSummary baseline,
                                       GenerationScenarioBucketSummary candidate,
                                       List<String> violations) {
        if (hasP95(baseline.latency().p95FirstUsefulMs())
                && hasP95(candidate.latency().p95FirstUsefulMs())
                && candidate.latency().p95FirstUsefulMs() > baseline.latency().p95FirstUsefulMs()) {
            violations.add("first_useful_p95_regressed");
        }
        if (hasP95(baseline.latency().p95DeliveredMs())
                && hasP95(candidate.latency().p95DeliveredMs())
                && candidate.latency().p95DeliveredMs() > baseline.latency().p95DeliveredMs()) {
            violations.add("delivered_p95_regressed");
        }
    }

    private void assessRelativeCost(GenerationScenarioBucketSummary baseline,
                                    GenerationScenarioBucketSummary candidate,
                                    List<String> violations) {
        double baseTokens = baseline.cost().averageProviderTokens(baseline.quality().taskCount());
        double nextTokens = candidate.cost().averageProviderTokens(candidate.quality().taskCount());
        if (nextTokens > baseTokens) {
            violations.add("average_provider_tokens_regressed");
        }
        double baseCredit = baseline.cost().averageCreditCost(baseline.quality().taskCount());
        double nextCredit = candidate.cost().averageCreditCost(candidate.quality().taskCount());
        if (nextCredit > baseCredit) {
            violations.add("average_credit_cost_regressed");
        }
    }

    private boolean hasObservedImprovement(GenerationScenarioBucketSummary baseline,
                                           GenerationScenarioBucketSummary candidate) {
        GenerationScenarioQualityMetrics baseQuality = baseline.quality();
        GenerationScenarioQualityMetrics nextQuality = candidate.quality();
        return nextQuality.successRate() > baseQuality.successRate()
                || nextQuality.firstBuildPassRate() > baseQuality.firstBuildPassRate()
                || greaterThan(nextQuality.averageRating(), baseQuality.averageRating())
                || nextQuality.lowRatingRate() < baseQuality.lowRatingRate()
                || nextQuality.averageRepairRounds() < baseQuality.averageRepairRounds()
                || lessThan(candidate.latency().p95FirstUsefulMs(), baseline.latency().p95FirstUsefulMs())
                || lessThan(candidate.latency().p95DeliveredMs(), baseline.latency().p95DeliveredMs())
                || candidate.cost().averageProviderTokens(nextQuality.taskCount())
                < baseline.cost().averageProviderTokens(baseQuality.taskCount())
                || candidate.cost().averageCreditCost(nextQuality.taskCount())
                < baseline.cost().averageCreditCost(baseQuality.taskCount());
    }

    private boolean greaterThan(Double candidate, Double baseline) {
        return candidate != null && baseline != null && candidate > baseline;
    }

    private boolean lessThan(Long candidate, Long baseline) {
        return candidate != null && baseline != null && candidate < baseline;
    }

    private boolean hasP95(Long value) {
        return value != null && value >= 0;
    }

    private boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
