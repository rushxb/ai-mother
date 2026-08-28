package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties;
import com.rush.rushaicodemother.orchestration.economics.GenerationDeliveryEconomics;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
        assessRelativeCapacity(baseline, candidate, violations);
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
        if (summary.capacity().observedTaskCount() < quality.taskCount()) {
            violations.add(role + "_capacity_observation_incomplete");
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
        GenerationScenarioLatencyMetrics latency = candidate.latency();
        // P95 即使优于基线，也不能超过更宽松的 P99 发布上限，否则慢基线会掩盖不可接受的绝对耗时。
        if (exceedsLatencyBudget(
                latency.p95FirstUsefulMs(), releaseProperties.getMaximumP99FirstPreviewLatency())) {
            violations.add("candidate_first_useful_p95_above_budget");
        }
        if (exceedsLatencyBudget(
                latency.p95DeliveredMs(), releaseProperties.getMaximumP99Duration())) {
            violations.add("candidate_delivered_p95_above_budget");
        }
        GenerationDeliveryEconomics economics = candidate.deliveryEconomics();
        if (!economics.isAvailable()) {
            violations.add("candidate_unit_success_cost_unavailable");
        } else if (exceeds(economics.providerTokensPerSuccessfulDelivery(),
                releaseProperties.maximumTokensPerSuccessfulDelivery())) {
            violations.add("candidate_provider_tokens_per_success_above_budget");
        }
        if (economics.isAvailable()
                && exceeds(economics.creditCostPerSuccessfulDelivery(),
                releaseProperties.maximumCreditCostPerSuccessfulDelivery())) {
            violations.add("candidate_credit_cost_per_success_above_budget");
        }
        GenerationScenarioCapacityMetrics capacity = candidate.capacity();
        if (capacity.maximumPhysicalModelCallsPerTask()
                > releaseProperties.getMaximumPhysicalModelCallsPerTask()) {
            violations.add("candidate_physical_model_calls_per_task_above_maximum");
        }
        Double callsPerSuccess = capacity.physicalModelCallsPerSuccessfulDelivery(
                quality.successCount());
        if (callsPerSuccess == null) {
            violations.add("candidate_unit_success_capacity_unavailable");
        } else if (callsPerSuccess
                > releaseProperties.getMaximumPhysicalModelCallsPerSuccessfulDelivery()) {
            violations.add("candidate_physical_model_calls_per_success_above_maximum");
        }
        if (capacity.capacityFailureRate(quality.taskCount())
                > releaseProperties.getMaximumCapacityFailureRate()) {
            violations.add("candidate_capacity_failure_rate_above_maximum");
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
        GenerationDeliveryEconomics baseEconomics = baseline.deliveryEconomics();
        GenerationDeliveryEconomics nextEconomics = candidate.deliveryEconomics();
        if (greaterThan(nextEconomics.providerTokensPerSuccessfulDelivery(),
                baseEconomics.providerTokensPerSuccessfulDelivery())) {
            violations.add("provider_tokens_per_success_regressed");
        }
        if (greaterThan(nextEconomics.creditCostPerSuccessfulDelivery(),
                baseEconomics.creditCostPerSuccessfulDelivery())) {
            violations.add("credit_cost_per_success_regressed");
        }
    }

    private void assessRelativeCapacity(GenerationScenarioBucketSummary baseline,
                                        GenerationScenarioBucketSummary candidate,
                                        List<String> violations) {
        Double baselineCalls = baseline.capacity().physicalModelCallsPerSuccessfulDelivery(
                baseline.quality().successCount());
        Double candidateCalls = candidate.capacity().physicalModelCallsPerSuccessfulDelivery(
                candidate.quality().successCount());
        if (greaterThan(candidateCalls, baselineCalls)) {
            violations.add("physical_model_calls_per_success_regressed");
        }
        if (candidate.capacity().capacityFailureRate(candidate.quality().taskCount())
                > baseline.capacity().capacityFailureRate(baseline.quality().taskCount())) {
            violations.add("capacity_failure_rate_regressed");
        }
    }

    private boolean hasObservedImprovement(GenerationScenarioBucketSummary baseline,
                                           GenerationScenarioBucketSummary candidate) {
        GenerationScenarioQualityMetrics baseQuality = baseline.quality();
        GenerationScenarioQualityMetrics nextQuality = candidate.quality();
        GenerationDeliveryEconomics baseEconomics = baseline.deliveryEconomics();
        GenerationDeliveryEconomics nextEconomics = candidate.deliveryEconomics();
        return nextQuality.successRate() > baseQuality.successRate()
                || nextQuality.firstBuildPassRate() > baseQuality.firstBuildPassRate()
                || greaterThan(nextQuality.averageRating(), baseQuality.averageRating())
                || nextQuality.lowRatingRate() < baseQuality.lowRatingRate()
                || nextQuality.averageRepairRounds() < baseQuality.averageRepairRounds()
                || lessThan(candidate.latency().p95FirstUsefulMs(), baseline.latency().p95FirstUsefulMs())
                || lessThan(candidate.latency().p95DeliveredMs(), baseline.latency().p95DeliveredMs())
                || lessThan(nextEconomics.providerTokensPerSuccessfulDelivery(),
                baseEconomics.providerTokensPerSuccessfulDelivery())
                || lessThan(nextEconomics.creditCostPerSuccessfulDelivery(),
                baseEconomics.creditCostPerSuccessfulDelivery())
                || lessThan(candidate.capacity().physicalModelCallsPerSuccessfulDelivery(
                        nextQuality.successCount()),
                baseline.capacity().physicalModelCallsPerSuccessfulDelivery(
                        baseQuality.successCount()))
                || candidate.capacity().capacityFailureRate(nextQuality.taskCount())
                < baseline.capacity().capacityFailureRate(baseQuality.taskCount());
    }

    private boolean greaterThan(Double candidate, Double baseline) {
        return candidate != null && baseline != null && candidate > baseline;
    }

    private boolean lessThan(Long candidate, Long baseline) {
        return candidate != null && baseline != null && candidate < baseline;
    }

    private boolean lessThan(Double candidate, Double baseline) {
        return candidate != null && baseline != null && candidate < baseline;
    }

    private boolean exceeds(Double actual, long maximum) {
        return actual != null && actual > maximum;
    }

    private boolean exceedsLatencyBudget(Long actualMs, Duration maximum) {
        return actualMs != null
                && (maximum == null || Duration.ofMillis(actualMs).compareTo(maximum) > 0);
    }

    private boolean hasP95(Long value) {
        return value != null && value >= 0;
    }

    private boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
