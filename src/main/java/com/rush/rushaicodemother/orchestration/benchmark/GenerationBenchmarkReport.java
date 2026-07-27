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
        List<GenerationBenchmarkRunResult> results
) {
    public static final int CURRENT_SCHEMA_VERSION = 4;

    public GenerationBenchmarkReport {
        promptBundleId = promptBundleId == null ? "" : promptBundleId;
        modelFingerprint = modelFingerprint == null ? "" : modelFingerprint;
        qualityStats = qualityStats == null ? Map.of() : Map.copyOf(qualityStats);
        modeStats = modeStats == null ? Map.of() : Map.copyOf(modeStats);
        results = results == null ? List.of() : List.copyOf(results);
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
}
