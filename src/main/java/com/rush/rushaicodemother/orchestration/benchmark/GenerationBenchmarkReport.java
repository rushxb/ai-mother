package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.orchestration.economics.GenerationDeliveryEconomics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        GenerationDeliveryEconomics deliveryEconomics,
        RouteStats routeStats,
        List<GenerationBenchmarkRunResult> results
) {
    public static final int CURRENT_SCHEMA_VERSION = 6;

    public GenerationBenchmarkReport {
        promptBundleId = promptBundleId == null ? "" : promptBundleId;
        modelFingerprint = modelFingerprint == null ? "" : modelFingerprint;
        qualityStats = qualityStats == null ? Map.of() : Map.copyOf(qualityStats);
        modeStats = modeStats == null ? Map.of() : Map.copyOf(modeStats);
        GenerationDeliveryEconomics expectedEconomics = GenerationDeliveryEconomics.fromTotals(
                successCount, totalTokens, totalCreditCost);
        if (deliveryEconomics == null) {
            deliveryEconomics = expectedEconomics;
        } else if (!deliveryEconomics.equals(expectedEconomics)) {
            throw new IllegalArgumentException("单位成功交付成本与报告汇总不一致");
        }
        results = results == null ? List.of() : List.copyOf(results);
        routeStats = routeStats == null ? routeStatsFrom(results) : routeStats;
    }

    /** 兼容尚未携带单位成功交付经济性的 schema 5 构造调用。 */
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
                                     RouteStats routeStats,
                                     List<GenerationBenchmarkRunResult> results) {
        this(schemaVersion, totalTasks, successCount, buildPassedCount, successRate, buildPassRate,
                averageDurationMs, p50DurationMs, p90DurationMs, p99DurationMs, aiCallCount,
                toolCallCount, fallbackCount, repairRounds, totalTokens, totalCreditCost,
                averageFirstTokenLatencyMs, p90FirstTokenLatencyMs, p99FirstTokenLatencyMs,
                firstPreviewObservedCount, firstPreviewObservationRate, averageFirstPreviewLatencyMs,
                p90FirstPreviewLatencyMs, p99FirstPreviewLatencyMs, promptBundleId, modelFingerprint,
                qualityStats, modeStats,
                GenerationDeliveryEconomics.fromTotals(successCount, totalTokens, totalCreditCost),
                routeStats, results);
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
                qualityStats, modeStats,
                GenerationDeliveryEconomics.fromTotals(successCount, totalTokens, totalCreditCost),
                routeStatsFrom(results), results);
    }

    private static RouteStats routeStatsFrom(List<GenerationBenchmarkRunResult> results) {
        return RouteStats.from(results);
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
            double wrongDegradationRate,
            Map<String, Map<String, Integer>> confusionMatrix
    ) {

        public RouteStats {
            if (expectedCount < 0 || allowedCount < 0
                    || wrongEscalationCount < 0 || wrongDegradationCount < 0
                    || allowedCount > expectedCount) {
                throw new IllegalArgumentException("路由统计数量不合法");
            }
            if (!validRate(accuracy) || !validRate(wrongEscalationRate)
                    || !validRate(wrongDegradationRate)) {
                throw new IllegalArgumentException("路由统计比例必须位于 0 到 1 之间");
            }
            confusionMatrix = immutableMatrix(confusionMatrix);
        }

        /** 兼容 schema 5 及进程内旧构造入口；新报告由结果明细生成混淆矩阵。 */
        public RouteStats(int expectedCount,
                          int allowedCount,
                          int wrongEscalationCount,
                          int wrongDegradationCount,
                          double accuracy,
                          double wrongEscalationRate,
                          double wrongDegradationRate) {
            this(expectedCount, allowedCount, wrongEscalationCount, wrongDegradationCount,
                    accuracy, wrongEscalationRate, wrongDegradationRate, Map.of());
        }

        /** 从可回放的逐任务事实生成路由统计，报告层不重新执行任何路由决策。 */
        public static RouteStats from(List<GenerationBenchmarkRunResult> results) {
            List<GenerationBenchmarkRunResult> routedResults = results == null
                    ? List.of()
                    : results.stream()
                    .filter(result -> result != null && !result.expectedRoute().isBlank())
                    .toList();
            int expected = routedResults.size();
            int allowed = (int) routedResults.stream()
                    .filter(GenerationBenchmarkRunResult::routeAllowed)
                    .count();
            int escalations = (int) routedResults.stream()
                    .filter(result -> !result.routeAllowed())
                    .filter(result -> routeRank(result.mode()) > routeRank(result.expectedRoute()))
                    .count();
            int degradations = (int) routedResults.stream()
                    .filter(result -> !result.routeAllowed())
                    .filter(result -> routeRank(result.mode()) < routeRank(result.expectedRoute()))
                    .count();
            return new RouteStats(
                    expected,
                    allowed,
                    escalations,
                    degradations,
                    rate(allowed, expected),
                    rate(escalations, expected),
                    rate(degradations, expected),
                    confusionMatrixFrom(routedResults));
        }

        public static RouteStats empty() {
            return new RouteStats(0, 0, 0, 0, 0.0, 0.0, 0.0, Map.of());
        }

        private static Map<String, Map<String, Integer>> confusionMatrixFrom(
                List<GenerationBenchmarkRunResult> results) {
            Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();
            for (GenerationBenchmarkRunResult result : results) {
                String expected = normalizeRoute(result.expectedRoute(), "UNSPECIFIED");
                String actual = normalizeRoute(result.mode(), "UNRESOLVED");
                matrix.computeIfAbsent(expected, ignored -> new LinkedHashMap<>())
                        .merge(actual, 1, Integer::sum);
            }
            return matrix;
        }

        private static Map<String, Map<String, Integer>> immutableMatrix(
                Map<String, Map<String, Integer>> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<String, Map<String, Integer>> copy = new LinkedHashMap<>();
            source.forEach((expected, actualCounts) -> {
                String expectedRoute = normalizeRoute(expected, "UNSPECIFIED");
                Map<String, Integer> row = new LinkedHashMap<>();
                if (actualCounts != null) {
                    actualCounts.forEach((actual, count) -> {
                        if (count == null || count <= 0) {
                            throw new IllegalArgumentException("路由混淆矩阵计数必须大于 0");
                        }
                        row.put(normalizeRoute(actual, "UNRESOLVED"), count);
                    });
                }
                copy.put(expectedRoute, Collections.unmodifiableMap(row));
            });
            return Collections.unmodifiableMap(copy);
        }

        private static String normalizeRoute(String route, String fallback) {
            if (route == null || route.isBlank()) {
                return fallback;
            }
            return route.trim().toUpperCase(Locale.ROOT);
        }

        private static int routeRank(String route) {
            return switch (normalizeRoute(route, "")) {
                case "CREATE" -> 1;
                case "LIGHT_EDIT" -> 2;
                case "AGENT_EDIT" -> 3;
                case "HEAVY_EXPERT" -> 4;
                default -> 0;
            };
        }

        private static double rate(int count, int total) {
            return total == 0 ? 0.0 : (double) count / total;
        }

        private static boolean validRate(double value) {
            return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
        }
    }
}
