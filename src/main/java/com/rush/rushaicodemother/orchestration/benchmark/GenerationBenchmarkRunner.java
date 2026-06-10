package com.rush.rushaicodemother.orchestration.benchmark;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class GenerationBenchmarkRunner {

    private final GenerationBenchmarkCatalog catalog;

    public GenerationBenchmarkRunner(GenerationBenchmarkCatalog catalog) {
        this.catalog = catalog;
    }

    public GenerationBenchmarkReport run(GenerationBenchmarkExecutor executor) {
        if (executor == null) {
            return summarize(List.of());
        }
        List<GenerationBenchmarkRunResult> results = catalog.tasks().stream()
                .map(executor::execute)
                .filter(result -> result != null)
                .toList();
        return summarize(results);
    }

    public GenerationBenchmarkReport summarize(List<GenerationBenchmarkRunResult> results) {
        List<GenerationBenchmarkRunResult> safeResults = results == null ? List.of() : results;
        Map<String, GenerationBenchmarkReport.ModeStats> modeStats = safeResults.stream()
                .collect(Collectors.groupingBy(
                        GenerationBenchmarkRunResult::mode,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), this::summarizeMode)
                ));
        return new GenerationBenchmarkReport(
                safeResults.size(),
                countSuccess(safeResults),
                countBuildPassed(safeResults),
                rate(countSuccess(safeResults), safeResults.size()),
                rate(countBuildPassed(safeResults), safeResults.size()),
                averageDuration(safeResults),
                percentileDuration(safeResults, 0.50),
                percentileDuration(safeResults, 0.90),
                safeResults.stream().mapToInt(GenerationBenchmarkRunResult::aiCallCount).sum(),
                safeResults.stream().mapToInt(GenerationBenchmarkRunResult::toolCallCount).sum(),
                (int) safeResults.stream().filter(GenerationBenchmarkRunResult::fallback).count(),
                safeResults.stream().mapToInt(GenerationBenchmarkRunResult::repairRounds).sum(),
                modeStats,
                safeResults
        );
    }

    private GenerationBenchmarkReport.ModeStats summarizeMode(List<GenerationBenchmarkRunResult> results) {
        return new GenerationBenchmarkReport.ModeStats(
                results.size(),
                countSuccess(results),
                countBuildPassed(results),
                rate(countSuccess(results), results.size()),
                rate(countBuildPassed(results), results.size()),
                averageDuration(results),
                percentileDuration(results, 0.50),
                percentileDuration(results, 0.90),
                (int) results.stream().filter(GenerationBenchmarkRunResult::fallback).count()
        );
    }

    private int countSuccess(List<GenerationBenchmarkRunResult> results) {
        return (int) results.stream().filter(GenerationBenchmarkRunResult::success).count();
    }

    private int countBuildPassed(List<GenerationBenchmarkRunResult> results) {
        return (int) results.stream().filter(GenerationBenchmarkRunResult::buildPassed).count();
    }

    private double rate(int count, int total) {
        return total == 0 ? 0 : (double) count / total;
    }

    private long averageDuration(List<GenerationBenchmarkRunResult> results) {
        return Math.round(results.stream()
                .mapToLong(GenerationBenchmarkRunResult::durationMs)
                .average()
                .orElse(0));
    }

    private long percentileDuration(List<GenerationBenchmarkRunResult> results, double percentile) {
        if (results == null || results.isEmpty()) {
            return 0;
        }
        List<Long> durations = results.stream()
                .map(GenerationBenchmarkRunResult::durationMs)
                .sorted(Comparator.naturalOrder())
                .toList();
        int index = (int) Math.ceil(percentile * durations.size()) - 1;
        return durations.get(Math.max(0, Math.min(index, durations.size() - 1)));
    }
}
