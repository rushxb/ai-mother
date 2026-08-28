package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRunResult;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidencePayload;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationVerifiedBenchmarkEvidence;
import com.rush.rushaicodemother.orchestration.release.GenerationExecutionReleaseIdentity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 把已验证的逐任务 Benchmark 事实关联到同一生产候选发布和路由。 */
@Component
public class GenerationOfflineOnlineCorrelationService {

    public GenerationOfflineOnlineCorrelation correlate(
            GenerationVerifiedBenchmarkEvidence verified,
            GenerationScenarioBucketSummary candidate,
            Instant onlineWindowStart
    ) {
        Objects.requireNonNull(verified, "已验证 Benchmark 证据不能为空");
        Objects.requireNonNull(candidate, "生产候选证据不能为空");
        Objects.requireNonNull(onlineWindowStart, "线上观测窗口起点不能为空");
        GenerationBenchmarkEvidencePayload payload = Objects.requireNonNull(
                verified.evidence().payload(), "Benchmark 证据载荷不能为空");
        requireSameRelease(payload, candidate.identity());
        if (payload.evaluatedAt() == null || payload.evaluatedAt().isAfter(onlineWindowStart)) {
            throw new IllegalArgumentException("Benchmark 必须先于线上候选观测窗口完成");
        }

        String route = normalizeRoute(candidate.identity().route());
        List<GenerationBenchmarkRunResult> routeResults = verified.report().results().stream()
                .filter(Objects::nonNull)
                .filter(result -> route.equals(normalizeRoute(result.mode())))
                .toList();
        if (routeResults.isEmpty()) {
            throw new IllegalArgumentException("Benchmark 未覆盖生产候选路由");
        }

        GenerationScenarioQualityMetrics online = candidate.quality();
        List<GenerationBenchmarkRunResult> qualityObserved = routeResults.stream()
                .filter(result -> !result.qualityEvidence().ruleResults().isEmpty())
                .toList();
        if (qualityObserved.isEmpty() || online.feedbackCount() == 0) {
            throw new IllegalArgumentException("离线质量或线上评分观测不足，无法解释低评分");
        }
        return new GenerationOfflineOnlineCorrelation(
                verified.evidence().evidenceId(),
                payload.datasetFingerprint(),
                payload.evaluatedAt(),
                route,
                routeResults.size(),
                comparison(
                        "benchmark_delivery_failure_rate",
                        routeResults.size(),
                        rate(routeResults.stream().filter(result -> !result.success()).count(),
                                routeResults.size()),
                        "production_delivery_failure_rate",
                        online.taskCount(),
                        rate(online.taskCount() - online.successCount(), online.taskCount())),
                comparison(
                        "benchmark_average_repair_rounds",
                        routeResults.size(),
                        averageRepairRounds(routeResults),
                        "production_average_repair_rounds",
                        online.repairObservedCount(),
                        online.averageRepairRounds()),
                comparison(
                        "benchmark_quality_failure_rate",
                        qualityObserved.size(),
                        rate(qualityObserved.stream()
                                        .filter(result -> !result.qualityEvidence().overallPassed())
                                        .count(),
                                qualityObserved.size()),
                        "production_low_rating_rate",
                        online.feedbackCount(),
                        online.lowRatingRate()),
                qualityFailureRates(routeResults));
    }

    private void requireSameRelease(GenerationBenchmarkEvidencePayload payload,
                                    GenerationScenarioBucketIdentity candidate) {
        String benchmarkRelease = new GenerationExecutionReleaseIdentity(
                payload.gitCommit(),
                false,
                payload.runtimeConfigFingerprint(),
                payload.promptBundleFingerprint(),
                payload.modelFingerprint(),
                candidate.decisionVersion())
                .releaseFingerprint();
        if (!benchmarkRelease.equals(candidate.releaseIdentity())) {
            throw new IllegalArgumentException(
                    "Benchmark 构建、策略、Prompt 或模型身份与生产候选不一致");
        }
    }

    private GenerationOfflineOnlineCorrelation.MetricComparison comparison(
            String offlineMetric,
            long offlineObservedCount,
            double offlineValue,
            String onlineMetric,
            long onlineObservedCount,
            double onlineValue
    ) {
        return new GenerationOfflineOnlineCorrelation.MetricComparison(
                offlineMetric, offlineObservedCount, offlineValue,
                onlineMetric, onlineObservedCount, onlineValue);
    }

    private double averageRepairRounds(List<GenerationBenchmarkRunResult> results) {
        return results.stream().mapToLong(GenerationBenchmarkRunResult::repairRounds).average()
                .orElse(0.0);
    }

    private Map<String, Double> qualityFailureRates(
            List<GenerationBenchmarkRunResult> results
    ) {
        Map<String, Double> rates = new TreeMap<>();
        for (GenerationBenchmarkQualityDimension dimension
                : GenerationBenchmarkQualityDimension.values()) {
            List<GenerationBenchmarkRunResult> observed = results.stream()
                    .filter(result -> result.qualityEvidence().evaluated(dimension))
                    .toList();
            if (!observed.isEmpty()) {
                long failures = observed.stream()
                        .filter(result -> !result.qualityEvidence().passed(dimension))
                        .count();
                rates.put(dimension.name().toLowerCase(Locale.ROOT),
                        rate(failures, observed.size()));
            }
        }
        return Map.copyOf(rates);
    }

    private String normalizeRoute(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private double rate(long count, long total) {
        return total == 0 ? 0.0 : (double) count / total;
    }
}
