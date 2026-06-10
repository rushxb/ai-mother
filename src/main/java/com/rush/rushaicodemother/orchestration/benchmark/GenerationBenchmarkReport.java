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
        int aiCallCount,
        int toolCallCount,
        int fallbackCount,
        int repairRounds,
        Map<String, ModeStats> modeStats,
        List<GenerationBenchmarkRunResult> results
) {
    public GenerationBenchmarkReport {
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
            int fallbackCount
    ) {
    }
}
