package com.rush.rushaicodemother.orchestration.benchmark;

import java.util.List;
import java.util.Map;

public record GenerationBenchmarkReport(
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
        String promptBundleId,
        Map<String, QualityStats> qualityStats,
        Map<String, ModeStats> modeStats,
        List<GenerationBenchmarkRunResult> results
) {
    public GenerationBenchmarkReport {
        promptBundleId = promptBundleId == null ? "" : promptBundleId;
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
            int fallbackCount
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
