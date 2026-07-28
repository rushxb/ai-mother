package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 根据端到端基准证据评估确定性发布门。 */
@Component
public class GenerationBenchmarkReleaseGate {

    private final GenerationBenchmarkReleaseProperties properties;

    public GenerationBenchmarkReleaseGate(GenerationBenchmarkReleaseProperties properties) {
        this.properties = properties;
    }

    /**
 * 返回{@code assess}。
 *
 * @param report 报告
 * @return 生成基准测试发布门禁
 */
    public GenerationBenchmarkReleaseAssessment assess(GenerationBenchmarkReport report) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (report == null) {
            throw new IllegalArgumentException("benchmark report cannot be null");
        }
        List<String> violations = new ArrayList<>();
        if (report.schemaVersion() != GenerationBenchmarkReport.CURRENT_SCHEMA_VERSION) {
            violations.add("report_schema_unsupported");
        }
        if (report.totalTasks() < properties.getMinimumTaskCount()) {
            violations.add("task_count_below_minimum");
        }
        if (report.successRate() < properties.getMinimumSuccessRate()) {
            violations.add("success_rate_below_minimum");
        }
        if (report.buildPassRate() < properties.getMinimumBuildPassRate()) {
            violations.add("build_pass_rate_below_minimum");
        }
        if (properties.isRequirePromptBundleId() && report.promptBundleId().isBlank()) {
            violations.add("prompt_bundle_missing");
        }
        assessQualityDimension(
                report, "structural",
                properties.getMinimumStructuralEvaluationRate(),
                properties.getMinimumStructuralPassRate(),
                violations
        );
        assessQualityDimension(
                report, "functional",
                properties.getMinimumFunctionalEvaluationRate(),
                properties.getMinimumFunctionalPassRate(),
                violations
        );
        assessQualityDimension(
                report, "diff_scope",
                properties.getMinimumDiffScopeEvaluationRate(),
                properties.getMinimumDiffScopePassRate(),
                violations
        );
        assessQualityDimension(
                report, "security",
                properties.getMinimumSecurityEvaluationRate(),
                properties.getMinimumSecurityPassRate(),
                violations
        );
        assessQualityDimension(
                report, "runtime",
                properties.getMinimumRuntimeEvaluationRate(),
                properties.getMinimumRuntimePassRate(),
                violations
        );
        assessQualityDimension(
                report, "visual",
                properties.getMinimumVisualEvaluationRate(),
                properties.getMinimumVisualPassRate(),
                violations
        );
        double fallbackRate = report.totalTasks() == 0
                ? 0.0
                : (double) report.fallbackCount() / report.totalTasks();
        if (fallbackRate > properties.getMaximumFallbackRate()) {
            violations.add("fallback_rate_above_maximum");
        }
        if (report.p90DurationMs() > properties.getMaximumP90Duration().toMillis()) {
            violations.add("p90_duration_above_maximum");
        }
        if (report.p99DurationMs() > properties.getMaximumP99Duration().toMillis()) {
            violations.add("p99_duration_above_maximum");
        }
        if (report.p90FirstTokenLatencyMs() > properties.getMaximumP90FirstTokenLatency().toMillis()) {
            violations.add("p90_first_token_latency_above_maximum");
        }
        if (report.p99FirstTokenLatencyMs() > properties.getMaximumP99FirstTokenLatency().toMillis()) {
            violations.add("p99_first_token_latency_above_maximum");
        }
        if (report.firstPreviewObservationRate() < properties.getMinimumFirstPreviewObservationRate()) {
            violations.add("first_preview_observation_rate_below_minimum");
        }
        if (report.p99FirstPreviewLatencyMs() > properties.getMaximumP99FirstPreviewLatency().toMillis()) {
            violations.add("p99_first_preview_latency_above_maximum");
        }
        assessFirstPreviewModes(report.modeStats(), violations);
        if (report.totalTasks() > 0
                && (double) report.totalTokens() / report.totalTasks() > properties.getMaximumAverageTokens()) {
            violations.add("average_tokens_above_maximum");
        }
        if (report.totalTasks() > 0
                && (double) report.totalCreditCost() / report.totalTasks()
                > properties.getMaximumAverageCreditCost()) {
            violations.add("average_credit_cost_above_maximum");
        }
        return new GenerationBenchmarkReleaseAssessment(violations.isEmpty(), violations, report);
    }

    /** 处理{@code assess}{@code First}预览{@code Modes}。 */
    private void assessFirstPreviewModes(
            Map<String, GenerationBenchmarkReport.ModeStats> modeStats,
            List<String> violations
    ) {
        for (Map.Entry<String, GenerationBenchmarkReport.ModeStats> entry : modeStats.entrySet()) {
            GenerationMode mode;
            try {
                mode = GenerationMode.valueOf(entry.getKey());
            } catch (IllegalArgumentException invalidMode) {
                violations.add("first_preview_mode_unsupported");
                continue;
            }
            GenerationBenchmarkReport.ModeStats stats = entry.getValue();
            String violationPrefix = mode.name().toLowerCase(Locale.ROOT);
            if (stats.firstPreviewObservationRate() < properties.getMinimumFirstPreviewObservationRate()) {
                violations.add(violationPrefix + "_first_preview_observation_rate_below_minimum");
            }
            Duration maximum = properties.getMaximumP90FirstPreviewLatencyByMode().get(mode);
            if (maximum == null) {
                violations.add(violationPrefix + "_first_preview_latency_limit_missing");
            } else if (stats.p90FirstPreviewLatencyMs() > maximum.toMillis()) {
                violations.add(violationPrefix + "_p90_first_preview_latency_above_maximum");
            }
        }
    }

    /** 处理{@code assess}质量{@code Dimension}。 */
    private void assessQualityDimension(GenerationBenchmarkReport report,
                                        String dimension,
                                        double minimumEvaluationRate,
                                        double minimumPassRate,
                                        List<String> violations) {
        GenerationBenchmarkReport.QualityStats stats = report.qualityStats().get(dimension);
        if (stats == null || stats.evaluationRate() < minimumEvaluationRate) {
            violations.add(dimension + "_evaluation_rate_below_minimum");
        }
        if (stats == null || stats.passRate() < minimumPassRate) {
            violations.add(dimension + "_pass_rate_below_minimum");
        }
    }
}
