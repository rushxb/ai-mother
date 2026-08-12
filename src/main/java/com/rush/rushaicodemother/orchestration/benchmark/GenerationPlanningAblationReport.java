package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 三组规划层消融实验的可比较汇总。 */
public record GenerationPlanningAblationReport(
        Map<GenerationPlanningVariant, VariantStats> variants,
        Map<GenerationPlanningVariant, GenerationBenchmarkReport> sourceReports
) {

    public GenerationPlanningAblationReport {
        variants = variants == null ? Map.of() : Map.copyOf(variants);
        sourceReports = sourceReports == null ? Map.of() : Map.copyOf(sourceReports);
        if (!variants.keySet().equals(sourceReports.keySet())) {
            throw new IllegalArgumentException("规划消融汇总与源报告方案集合不一致");
        }
    }

    public static GenerationPlanningAblationReport from(
            Map<GenerationPlanningVariant, GenerationBenchmarkReport> reports) {
        if (reports == null || reports.size() != GenerationPlanningVariant.values().length) {
            throw new IllegalArgumentException("规划消融必须完整覆盖三种方案");
        }
        EnumMap<GenerationPlanningVariant, VariantStats> stats =
                new EnumMap<>(GenerationPlanningVariant.class);
        for (GenerationPlanningVariant variant : GenerationPlanningVariant.values()) {
            GenerationBenchmarkReport report = reports.get(variant);
            if (report == null || report.results().stream()
                    .anyMatch(result -> result.planningVariant() != variant)) {
                throw new IllegalArgumentException("规划消融源报告包含错误的方案身份");
            }
            stats.put(variant, VariantStats.from(report));
        }
        return new GenerationPlanningAblationReport(stats, reports);
    }

    public record VariantStats(
            int totalTasks,
            double successRate,
            double buildPassRate,
            int preparationObservedCount,
            double preparationObservationRate,
            long averagePreparationDurationMs,
            long p90PreparationDurationMs,
            long averageDurationMs,
            long p90DurationMs,
            long totalTokens,
            long totalCreditCost
    ) {
        private static VariantStats from(GenerationBenchmarkReport report) {
            List<GenerationBenchmarkRunResult> plannedResults = report.results().stream()
                    .filter(result -> "HEAVY_EXPERT".equals(result.mode()))
                    .toList();
            int observed = (int) plannedResults.stream()
                    .filter(result -> result.preparationDurationMs() != null)
                    .count();
            long averagePreparation = Math.round(plannedResults.stream()
                    .map(GenerationBenchmarkRunResult::preparationDurationMs)
                    .filter(Objects::nonNull)
                    .mapToLong(Long::longValue)
                    .average()
                    .orElse(0));
            List<Long> preparationDurations = plannedResults.stream()
                    .map(GenerationBenchmarkRunResult::preparationDurationMs)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.naturalOrder())
                    .toList();
            return new VariantStats(
                    report.totalTasks(),
                    report.successRate(),
                    report.buildPassRate(),
                    observed,
                    plannedResults.isEmpty() ? 0.0 : (double) observed / plannedResults.size(),
                    averagePreparation,
                    percentile(preparationDurations, 0.90),
                    report.averageDurationMs(),
                    report.p90DurationMs(),
                    report.totalTokens(),
                    report.totalCreditCost()
            );
        }

        private static long percentile(List<Long> values, double percentile) {
            if (values.isEmpty()) {
                return 0;
            }
            int index = (int) Math.ceil(percentile * values.size()) - 1;
            return values.get(Math.max(0, Math.min(index, values.size() - 1)));
        }
    }
}
