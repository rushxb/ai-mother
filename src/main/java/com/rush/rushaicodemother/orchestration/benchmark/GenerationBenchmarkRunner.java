package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkModelFingerprintProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 生成基准测试运行器。
 */
@Component
public class GenerationBenchmarkRunner {

    private static final GenerationBenchmarkModelFingerprintProvider UNMANAGED_MODEL_FINGERPRINT_PROVIDER = () -> "";

    private final GenerationBenchmarkCatalog catalog;
    private final PromptCatalog promptCatalog;
    private final GenerationBenchmarkModelFingerprintProvider modelFingerprintProvider;
    private final Map<String, GenerationBenchmarkTask> tasksById;

    public GenerationBenchmarkRunner(GenerationBenchmarkCatalog catalog) {
        this(catalog, PromptCatalog.unmanaged(), UNMANAGED_MODEL_FINGERPRINT_PROVIDER);
    }

    public GenerationBenchmarkRunner(GenerationBenchmarkCatalog catalog, PromptCatalog promptCatalog) {
        this(catalog, promptCatalog, UNMANAGED_MODEL_FINGERPRINT_PROVIDER);
    }

    @Autowired
    public GenerationBenchmarkRunner(GenerationBenchmarkCatalog catalog,
                                     PromptCatalog promptCatalog,
                                     GenerationBenchmarkModelFingerprintProvider modelFingerprintProvider) {
        this.catalog = catalog;
        this.promptCatalog = promptCatalog == null ? PromptCatalog.unmanaged() : promptCatalog;
        this.modelFingerprintProvider = modelFingerprintProvider == null
                ? UNMANAGED_MODEL_FINGERPRINT_PROVIDER
                : modelFingerprintProvider;
        this.tasksById = catalog.tasks().stream().collect(Collectors.toUnmodifiableMap(
                GenerationBenchmarkTask::id,
                task -> task
        ));
    }

    /**
 * 运行生成基准测试处理流程。
 *
 * @param executor 执行器
 * @return 生成基准测试
 */
    public GenerationBenchmarkReport run(GenerationBenchmarkExecutor executor) {
        ExecutionIdentity before = currentExecutionIdentity();
        List<GenerationBenchmarkRunResult> results = executor == null
                ? List.of()
                : catalog.tasks().stream()
                .map(executor::execute)
                .filter(result -> result != null)
                .toList();
        ExecutionIdentity after = currentExecutionIdentity();
        if (!before.equals(after)) {
            throw new IllegalStateException(
                    "Benchmark 执行期间 Prompt 或模型配置发生变化，已拒绝生成报告");
        }
        return summarize(results, before.promptBundleId(), before.modelFingerprint());
    }

    /**
 * 计算{@code marize}的汇总值。
 *
 * @param results 待处理的 {@code results} 集合
 * @return {@code marize}
 */
    public GenerationBenchmarkReport summarize(List<GenerationBenchmarkRunResult> results) {
        return summarize(
                results,
                promptCatalog.bundleId(),
                modelFingerprintProvider.currentFingerprint()
        );
    }

    /** 计算{@code marize}的汇总值。 */
    GenerationBenchmarkReport summarize(List<GenerationBenchmarkRunResult> results,
                                        String promptBundleId,
                                        String modelFingerprint) {
        List<GenerationBenchmarkRunResult> safeResults = results == null ? List.of() : results;
        Map<String, GenerationBenchmarkReport.ModeStats> modeStats = safeResults.stream()
                .collect(Collectors.groupingBy(
                        GenerationBenchmarkRunResult::mode,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), this::summarizeMode)
                ));
        return new GenerationBenchmarkReport(
                GenerationBenchmarkReport.CURRENT_SCHEMA_VERSION,
                safeResults.size(),
                countSuccess(safeResults),
                countBuildPassed(safeResults),
                rate(countSuccess(safeResults), safeResults.size()),
                rate(countBuildPassed(safeResults), safeResults.size()),
                averageDuration(safeResults),
                percentileDuration(safeResults, 0.50),
                percentileDuration(safeResults, 0.90),
                percentileDuration(safeResults, 0.99),
                safeResults.stream().mapToInt(GenerationBenchmarkRunResult::aiCallCount).sum(),
                safeResults.stream().mapToInt(GenerationBenchmarkRunResult::toolCallCount).sum(),
                (int) safeResults.stream().filter(GenerationBenchmarkRunResult::fallback).count(),
                safeResults.stream().mapToInt(GenerationBenchmarkRunResult::repairRounds).sum(),
                safeResults.stream().mapToLong(GenerationBenchmarkRunResult::totalTokens).sum(),
                safeResults.stream().mapToLong(GenerationBenchmarkRunResult::creditCost).sum(),
                averageFirstTokenLatency(safeResults),
                percentileFirstTokenLatency(safeResults, 0.90),
                percentileFirstTokenLatency(safeResults, 0.99),
                countFirstPreviewObserved(safeResults),
                rate(countFirstPreviewObserved(safeResults), safeResults.size()),
                averageFirstPreviewLatency(safeResults),
                percentileFirstPreviewLatency(safeResults, 0.90),
                percentileFirstPreviewLatency(safeResults, 0.99),
                promptBundleId,
                modelFingerprint,
                summarizeQuality(safeResults),
                modeStats,
                summarizeRoutes(safeResults),
                safeResults
        );
    }

    /** 计算{@code marize}模式的汇总值。 */
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
                percentileDuration(results, 0.99),
                (int) results.stream().filter(GenerationBenchmarkRunResult::fallback).count(),
                countFirstPreviewObserved(results),
                rate(countFirstPreviewObserved(results), results.size()),
                averageFirstPreviewLatency(results),
                percentileFirstPreviewLatency(results, 0.90),
                percentileFirstPreviewLatency(results, 0.99)
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

    private long averageFirstTokenLatency(List<GenerationBenchmarkRunResult> results) {
        return Math.round(results.stream()
                .mapToLong(GenerationBenchmarkRunResult::firstTokenLatencyMs)
                .filter(value -> value > 0)
                .average()
                .orElse(0));
    }

    private long percentileFirstTokenLatency(List<GenerationBenchmarkRunResult> results, double percentile) {
        List<Long> values = results.stream()
                .map(GenerationBenchmarkRunResult::firstTokenLatencyMs)
                .filter(value -> value > 0)
                .sorted()
                .toList();
        if (values.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private int countFirstPreviewObserved(List<GenerationBenchmarkRunResult> results) {
        return (int) results.stream()
                .filter(GenerationBenchmarkRunResult::firstPreviewObserved)
                .count();
    }

    private long averageFirstPreviewLatency(List<GenerationBenchmarkRunResult> results) {
        return Math.round(results.stream()
                .map(GenerationBenchmarkRunResult::firstPreviewLatencyMs)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .average()
                .orElse(0));
    }

    private long percentileFirstPreviewLatency(List<GenerationBenchmarkRunResult> results,
                                               double percentile) {
        List<Long> values = results.stream()
                .map(GenerationBenchmarkRunResult::firstPreviewLatencyMs)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        if (values.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    /** 计算{@code marize}质量的汇总值。 */
    private Map<String, GenerationBenchmarkReport.QualityStats> summarizeQuality(
            List<GenerationBenchmarkRunResult> results
    ) {
        Map<String, GenerationBenchmarkReport.QualityStats> qualityStats = new LinkedHashMap<>();
        for (GenerationBenchmarkQualityDimension dimension : GenerationBenchmarkQualityDimension.values()) {
            List<GenerationBenchmarkRunResult> eligibleResults = results.stream()
                    .filter(result -> requiresDimension(result.taskId(), dimension))
                    .toList();
            int evaluated = (int) results.stream()
                    .filter(result -> requiresDimension(result.taskId(), dimension))
                    .filter(result -> result.qualityEvidence().evaluated(dimension))
                    .count();
            int passed = (int) results.stream()
                    .filter(result -> requiresDimension(result.taskId(), dimension))
                    .filter(result -> result.qualityEvidence().passed(dimension))
                    .count();
            qualityStats.put(dimension.name().toLowerCase(), new GenerationBenchmarkReport.QualityStats(
                    evaluated,
                    passed,
                    rate(evaluated, eligibleResults.size()),
                    rate(passed, evaluated)
            ));
        }
        return Map.copyOf(qualityStats);
    }

    private boolean requiresDimension(String taskId, GenerationBenchmarkQualityDimension dimension) {
        GenerationBenchmarkTask task = tasksById.get(taskId);
        return task == null
                || task.requiredQualityDimensions().isEmpty()
                || task.requiredQualityDimensions().contains(dimension);
    }

    private GenerationBenchmarkReport.RouteStats summarizeRoutes(List<GenerationBenchmarkRunResult> results) {
        return GenerationBenchmarkReport.RouteStats.from(results);
    }

    private ExecutionIdentity currentExecutionIdentity() {
        return new ExecutionIdentity(
                promptCatalog.bundleId(),
                modelFingerprintProvider.currentFingerprint()
        );
    }

    private record ExecutionIdentity(String promptBundleId, String modelFingerprint) {
    }
}
