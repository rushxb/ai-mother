package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.mapper.GenerationScenarioAttributionMapper;
import com.rush.rushaicodemother.mapper.GenerationScenarioBucketRow;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioAttribution;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioAttributionRepository;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioBucketIdentity;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioBucketSummary;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioCostMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioLatencyMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioQualityMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MyBatisGenerationScenarioAttributionRepository
        implements GenerationScenarioAttributionRepository {

    private static final int MAX_LIMIT = 500;
    private static final Duration MAX_QUERY_WINDOW = Duration.ofDays(90);
    private final GenerationScenarioAttributionMapper mapper;
    private final ZoneId databaseZone = ZoneId.systemDefault();

    @Override
    public Optional<GenerationScenarioAttribution> findByTaskId(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectByTaskId(taskId));
    }

    @Override
    public List<GenerationScenarioBucketSummary> summarize(String intentSignature,
                                                            Instant from,
                                                            Instant to,
                                                            int limit) {
        if (from == null || to == null || !to.isAfter(from)
                || Duration.between(from, to).compareTo(MAX_QUERY_WINDOW) > 0
                || limit <= 0 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("场景归因查询范围不合法");
        }
        if (intentSignature == null || !intentSignature.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("场景签名不合法");
        }
        List<GenerationScenarioBucketRow> result = mapper.summarize(
                intentSignature, toLocal(from), toLocal(to), limit);
        return result == null ? List.of() : result.stream().map(this::toSummary).toList();
    }

    private GenerationScenarioBucketSummary toSummary(GenerationScenarioBucketRow row) {
        if (row == null) {
            throw new IllegalStateException("场景归因查询返回空行");
        }
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
                        count(row.creditCostObservedCount()), count(row.totalCreditCost())));
    }

    private long count(Long value) {
        return value == null ? 0L : value;
    }

    private LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, databaseZone);
    }
}
