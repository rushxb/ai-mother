package com.rush.rushaicodemother.orchestration.benchmark;

import java.util.List;
import java.util.Map;

/**
 * 生成基准测试报告的不可变数据载体。
 */
public record GenerationBenchmarkReport(
        int schemaVersion,
        int totalTasks,
        int successCount,
        int buildPassedCount,
        double successRate,
        double buildPassRate,
        long averageDurationMs,
        long p50DurationMs,
        long p90DurationMs,
        long p99DurationMs,
        int aiCallCount,
        int toolCallCount,
        int fallbackCount,
        int repairRounds,
        long totalTokens,
        long totalCreditCost,
        long averageFirstTokenLatencyMs,
        long p90FirstTokenLatencyMs,
        long p99FirstTokenLatencyMs,
        int firstPreviewObservedCount,
        double firstPreviewObservationRate,
        long averageFirstPreviewLatencyMs,
        long p90FirstPreviewLatencyMs,
        long p99FirstPreviewLatencyMs,
        String promptBundleId,
        String modelFingerprint,
        Map<String, QualityStats> qualityStats,
        Map<String, ModeStats> modeStats,
        RouteStats routeStats,
        List<GenerationBenchmarkRunResult> results
) {
    public static final int CURRENT_SCHEMA_VERSION = 5;

    public GenerationBenchmarkReport {
        promptBundleId = promptBundleId == null ? "" : promptBundleId;
        modelFingerprint = modelFingerprint == null ? "" : modelFingerprint;
        qualityStats = qualityStats == null ? Map.of() : Map.copyOf(qualityStats);
        modeStats = modeStats == null ? Map.of() : Map.copyOf(modeStats);
        routeStats = routeStats == null ? routeStatsFrom(results) : routeStats;
        results = results == null ? List.of() : List.copyOf(results);
    }

    /** 兼容历史报告构造器，旧报告没有路由质量统计。 */
    public GenerationBenchmarkReport(int schemaVersion,
                                     int totalTasks,
                                     int successCount,
                                     int buildPassedCount,
                                     double successRate,
                                     double buildPassRate,
                                     long averageDurationMs,
                                     long p50DurationMs,
                                     long p90DurationMs,
                                     long p99DurationMs,
                                     int aiCallCount,
                                     int toolCallCount,
                                     int fallbackCount,
                                     int repairRounds,
                                     long totalTokens,
                                     long totalCreditCost,
                                     long averageFirstTokenLatencyMs,
                                     long p90FirstTokenLatencyMs,
                                     long p99FirstTokenLatencyMs,
                                     int firstPreviewObservedCount,
                                     double firstPreviewObservationRate,
                                     long averageFirstPreviewLatencyMs,
                                     long p90FirstPreviewLatencyMs,
                                     long p99FirstPreviewLatencyMs,
                                     String promptBundleId,
                                     String modelFingerprint,
                                     Map<String, QualityStats> qualityStats,
                                     Map<String, ModeStats> modeStats,
                                     List<GenerationBenchmarkRunResult> results) {
        this(schemaVersion, totalTasks, successCount, buildPassedCount, successRate, buildPassRate,
                averageDurationMs, p50DurationMs, p90DurationMs, p99DurationMs, aiCallCount,
                toolCallCount, fallbackCount, repairRounds, totalTokens, totalCreditCost,
                averageFirstTokenLatencyMs, p90FirstTokenLatencyMs, p99FirstTokenLatencyMs,
                firstPreviewObservedCount, firstPreviewObservationRate, averageFirstPreviewLatencyMs,
                p90FirstPreviewLatencyMs, p99FirstPreviewLatencyMs, promptBundleId, modelFingerprint,
                qualityStats, modeStats, routeStatsFrom(results), results);
    }

    private static RouteStats routeStatsFrom(List<GenerationBenchmarkRunResult> results) {
        if (results == null || results.isEmpty()) {
            return RouteStats.empty();
        }
        int expected = (int) results.stream().filter(result -> result != null
                && result.expectedRoute() != null && !result.expectedRoute().isBlank()).count();
        int allowed = (int) results.stream().filter(result -> result != null && result.routeAllowed()).count();
        return new RouteStats(expected, Math.min(expected, allowed), 0, 0,
                expected == 0 ? 0.0 : (double) allowed / expected, 0.0, 0.0);
    }

    public record ModeStats(
            int totalTasks,
            int successCount,
            int buildPassedCount,
            double successRate,
            double buildPassRate,
            long averageDurationMs,
            long p50DurationMs,
            long p90DurationMs,
            long p99DurationMs,
            int fallbackCount,
            int firstPreviewObservedCount,
            double firstPreviewObservationRate,
            long averageFirstPreviewLatencyMs,
            long p90FirstPreviewLatencyMs,
            long p99FirstPreviewLatencyMs
    ) {
    }

    public record QualityStats(
            int evaluatedCount,
            int passedCount,
            double evaluationRate,
            double passRate
    ) {
    }

    /** 路由选择质量统计，用于发布门禁识别错误升级和错误降级。 */
    public record RouteStats(
            int expectedCount,
            int allowedCount,
            int wrongEscalationCount,
            int wrongDegradationCount,
            double accuracy,
            double wrongEscalationRate,
            double wrongDegradationRate
    ) {
        public static RouteStats empty() {
            return new RouteStats(0, 0, 0, 0, 0.0, 0.0, 0.0);
        }
    }
}
