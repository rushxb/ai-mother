package com.rush.rushaicodemother.orchestration.learning;

import java.time.Instant;
import java.util.Map;

/**
 * 同一候选发布在离线 Benchmark 与生产观测中的可解释维度。
 *
 * <p>这里只保留能与生产失败、返工和低评分直接对照的指标，避免把无法解释
 * 线上结果的离线统计混入晋级结论。</p>
 */
public record GenerationOfflineOnlineCorrelation(
        String evidenceId,
        String datasetFingerprint,
        Instant evaluatedAt,
        String route,
        long offlineTaskCount,
        MetricComparison deliveryFailure,
        MetricComparison averageRepairRounds,
        MetricComparison qualityRisk,
        Map<String, Double> offlineQualityFailureRates
) {

    public GenerationOfflineOnlineCorrelation {
        if (evidenceId == null || evidenceId.isBlank()) {
            throw new IllegalArgumentException("Benchmark 证据编号不能为空");
        }
        if (datasetFingerprint == null || !datasetFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Benchmark 数据集指纹无效");
        }
        if (evaluatedAt == null || route == null || route.isBlank() || offlineTaskCount <= 0) {
            throw new IllegalArgumentException("离线在线关联身份不完整");
        }
        offlineQualityFailureRates = offlineQualityFailureRates == null
                ? Map.of()
                : Map.copyOf(offlineQualityFailureRates);
    }

    /** 相同业务风险在离线与在线两侧的观测口径和值。 */
    public record MetricComparison(
            String offlineMetric,
            long offlineObservedCount,
            double offlineValue,
            String onlineMetric,
            long onlineObservedCount,
            double onlineValue
    ) {

        public MetricComparison {
            if (offlineMetric == null || offlineMetric.isBlank()
                    || onlineMetric == null || onlineMetric.isBlank()) {
                throw new IllegalArgumentException("关联指标名称不能为空");
            }
            if (offlineObservedCount <= 0 || onlineObservedCount <= 0
                    || !Double.isFinite(offlineValue) || offlineValue < 0
                    || !Double.isFinite(onlineValue) || onlineValue < 0) {
                throw new IllegalArgumentException("关联指标观测值无效");
            }
        }
    }
}
