package com.rush.rushaicodemother.infrastructure.persistence.prompt;

import com.rush.rushaicodemother.mapper.GenerationScenarioBucketRow;
import com.rush.rushaicodemother.mapper.PromptCanaryAttributionExclusionRow;
import com.rush.rushaicodemother.mapper.PromptCanaryObservationMapper;
import com.rush.rushaicodemother.mapper.PromptCanaryObservationQuery;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.ReleaseCandidateFingerprint;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioBucketIdentity;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioBucketSummary;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioCapacityMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioCostMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioLatencyMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioQualityMetrics;
import com.rush.rushaicodemother.service.prompt.canary.PromptCanaryEvaluationRequest;
import com.rush.rushaicodemother.service.prompt.canary.PromptCanaryObservation;
import com.rush.rushaicodemother.service.prompt.canary.PromptCanaryObservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** Prompt 灰度观测的 MySQL 聚合适配器。 */
@Repository
@RequiredArgsConstructor
public class MyBatisPromptCanaryObservationRepository
        implements PromptCanaryObservationRepository {

    private static final Duration MAXIMUM_WINDOW = Duration.ofDays(90);
    private final PromptCanaryObservationMapper mapper;
    private final ZoneId databaseZone = ZoneId.systemDefault();

    @Override
    public PromptCanaryObservation observe(PromptCanaryEvaluationRequest request) {
        if (request == null || Duration.between(request.windowStart(), request.windowEnd())
                .compareTo(MAXIMUM_WINDOW) > 0) {
            throw new IllegalArgumentException("Prompt 灰度观测范围不合法");
        }
        PromptCanaryObservationQuery query = toQuery(request);
        List<GenerationScenarioBucketRow> rows = mapper.summarize(query);
        GenerationScenarioBucketSummary stable = findOrEmpty(
                rows, "stable", query.stableReleaseIdentity(), query);
        GenerationScenarioBucketSummary canary = findOrEmpty(
                rows, "canary", query.canaryReleaseIdentity(), query);
        PromptCanaryAttributionExclusionRow excluded = mapper.countExcluded(query);
        return new PromptCanaryObservation(
                stable,
                canary,
                count(excluded == null ? null : excluded.ambiguousTaskCount()),
                count(excluded == null ? null : excluded.invalidAttributionTaskCount())
        );
    }

    private PromptCanaryObservationQuery toQuery(PromptCanaryEvaluationRequest request) {
        String identitySeed = "prompt-canary-v1|" + request.promptKey() + "|"
                + request.bundleId() + "|" + request.bundleRevision();
        String intentSignature = ReleaseCandidateFingerprint.sha256(identitySeed);
        String stableIdentity = ReleaseCandidateFingerprint.sha256(
                identitySeed + "|stable|" + request.stableVersion() + "|"
                        + request.stableContentHash());
        String canaryIdentity = ReleaseCandidateFingerprint.sha256(
                identitySeed + "|canary|" + request.canaryVersion() + "|"
                        + request.canaryContentHash());
        return new PromptCanaryObservationQuery(
                request.promptKey(), request.stableVersion(), request.stableContentHash(),
                request.canaryVersion(), request.canaryContentHash(), request.bundleId(),
                intentSignature, Long.toString(request.bundleRevision()),
                stableIdentity, canaryIdentity,
                LocalDateTime.ofInstant(request.windowStart(), databaseZone),
                LocalDateTime.ofInstant(request.windowEnd(), databaseZone)
        );
    }

    private GenerationScenarioBucketSummary findOrEmpty(
            List<GenerationScenarioBucketRow> rows,
            String channel,
            String releaseIdentity,
            PromptCanaryObservationQuery query
    ) {
        List<GenerationScenarioBucketRow> safeRows = rows == null ? List.of() : rows;
        return safeRows.stream()
                .filter(row -> row != null && channel.equals(row.route()))
                .findFirst()
                .map(this::toSummary)
                .orElseGet(() -> emptySummary(channel, releaseIdentity, query));
    }

    private GenerationScenarioBucketSummary toSummary(GenerationScenarioBucketRow row) {
        return new GenerationScenarioBucketSummary(
                new GenerationScenarioBucketIdentity(
                        row.intentSignature(), row.profileVersion(), row.decisionVersion(),
                        row.route(), row.releaseIdentity()),
                new GenerationScenarioQualityMetrics(
                        count(row.taskCount()), count(row.successCount()),
                        count(row.validationRequiredCount()), count(row.validationObservedCount()),
                        count(row.firstBuildPassCount()), count(row.repairObservedCount()),
                        count(row.totalRepairRounds()), count(row.feedbackCount()),
                        count(row.lowRatingCount()), row.averageRating()),
                new GenerationScenarioLatencyMetrics(
                        count(row.firstUsefulObservedCount()), row.averageFirstUsefulMs(),
                        row.p95FirstUsefulMs(), count(row.deliveredObservedCount()),
                        row.averageDeliveredMs(), row.p95DeliveredMs()),
                new GenerationScenarioCostMetrics(
                        count(row.providerCostObservedCount()), count(row.totalProviderTokens()),
                        count(row.creditCostObservedCount()), count(row.totalCreditCost())),
                new GenerationScenarioCapacityMetrics(
                        count(row.capacityObservedTaskCount()),
                        count(row.totalPhysicalModelCalls()),
                        count(row.maximumPhysicalModelCallsPerTask()),
                        count(row.capacityFailureCount()))
        );
    }

    private GenerationScenarioBucketSummary emptySummary(
            String channel,
            String releaseIdentity,
            PromptCanaryObservationQuery query
    ) {
        return new GenerationScenarioBucketSummary(
                new GenerationScenarioBucketIdentity(
                        query.intentSignature(), "prompt-canary-v1", query.decisionVersion(),
                        channel, releaseIdentity),
                new GenerationScenarioQualityMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, null),
                new GenerationScenarioLatencyMetrics(0, null, null, 0, null, null),
                new GenerationScenarioCostMetrics(0, 0, 0, 0),
                new GenerationScenarioCapacityMetrics(0, 0, 0, 0)
        );
    }

    private long count(Long value) {
        return value == null ? 0L : value;
    }
}
