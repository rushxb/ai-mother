package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.monitor.GenerationBenchmarkGraderMetricsCollector;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 应用可组合夹具规则，捕获基线并对生成的工作空间进行分级。 */
@Slf4j
@Component
public class GenerationBenchmarkValidationEngine {

    private final List<GenerationBenchmarkValidationRule> rules;
    private final List<GenerationBenchmarkResponseRule> responseRules;
    private final List<GenerationBenchmarkRuntimeGrader> runtimeGraders;
    private final GenerationBenchmarkWorkspaceInspector inspector;
    private final GenerationBenchmarkGraderMetricsCollector metricsCollector;

    @Autowired
    public GenerationBenchmarkValidationEngine(
            List<GenerationBenchmarkValidationRule> rules,
            List<GenerationBenchmarkResponseRule> responseRules,
            List<GenerationBenchmarkRuntimeGrader> runtimeGraders,
            GenerationBenchmarkWorkspaceInspector inspector,
            GenerationBenchmarkGraderMetricsCollector metricsCollector
    ) {
        this.rules = rules == null ? List.of() : rules.stream()
                .sorted(Comparator.comparingInt(GenerationBenchmarkValidationRule::order)
                        .thenComparing(GenerationBenchmarkValidationRule::id))
                .toList();
        this.responseRules = responseRules == null ? List.of() : responseRules.stream()
                .sorted(Comparator.comparingInt(GenerationBenchmarkResponseRule::order)
                        .thenComparing(GenerationBenchmarkResponseRule::id))
                .toList();
        this.runtimeGraders = runtimeGraders == null ? List.of() : runtimeGraders.stream()
                .sorted(Comparator.comparingInt(GenerationBenchmarkRuntimeGrader::order)
                        .thenComparing(GenerationBenchmarkRuntimeGrader::id))
                .toList();
        this.inspector = inspector;
        this.metricsCollector = metricsCollector;
    }

    public GenerationBenchmarkValidationEngine(
            List<GenerationBenchmarkValidationRule> rules,
            List<GenerationBenchmarkRuntimeGrader> runtimeGraders,
            GenerationBenchmarkWorkspaceInspector inspector,
            GenerationBenchmarkGraderMetricsCollector metricsCollector
    ) {
        this(rules, List.of(), runtimeGraders, inspector, metricsCollector);
    }

    public GenerationBenchmarkValidationEngine(
            List<GenerationBenchmarkValidationRule> rules,
            GenerationBenchmarkWorkspaceInspector inspector
    ) {
        this(
                rules,
                List.of(),
                List.of(),
                inspector,
                GenerationBenchmarkGraderMetricsCollector.noOp()
        );
    }

    public GenerationBenchmarkValidationPlan prepare(
            GenerationBenchmarkTask task,
            GenerationWorkspace workspace
    ) {
        return prepare(task, workspace, 0L);
    }

    /**
 * 准备后续流程所需的生成基准测试校验。
 *
 * @param task 任务
 * @param workspace 工作区
 * @param userId 用户编号
 * @return 生成基准测试校验
 */
    public GenerationBenchmarkValidationPlan prepare(
            GenerationBenchmarkTask task,
            GenerationWorkspace workspace,
            long userId
    ) {
        if (task == null || workspace == null) {
            return GenerationBenchmarkValidationPlan.empty();
        }
        List<GenerationBenchmarkValidationRule> selected = rules.stream()
                .filter(rule -> rule.supports(task))
                .toList();
        List<GenerationBenchmarkRuntimeGrader> selectedRuntimeGraders = runtimeGraders.stream()
                .filter(grader -> grader.supports(task))
                .toList();
        selected.forEach(rule -> rule.prepare(task, workspace));
        GenerationBenchmarkWorkspaceSnapshot baseline =
                inspector.capture(workspace.canonicalRootPath());
        return new GenerationBenchmarkValidationPlan(
                task,
                workspace,
                baseline,
                selected,
                selectedRuntimeGraders,
                userId
        );
    }

    public GenerationBenchmarkQualityEvidence evaluate(GenerationBenchmarkValidationPlan plan) {
        return evaluate(plan, "");
    }

    /** 同时评估工作区事实和最终响应事实。 */
    public GenerationBenchmarkQualityEvidence evaluate(
            GenerationBenchmarkValidationPlan plan,
            String responseText
    ) {
        if (plan == null || plan.task() == null || plan.workspace() == null) {
            return GenerationBenchmarkQualityEvidence.empty();
        }
        List<GenerationBenchmarkRuleResult> results = new ArrayList<>();
        for (GenerationBenchmarkValidationRule rule : plan.rules()) {
            long startedAt = System.nanoTime();
            try {
                GenerationBenchmarkRuleResult result = rule.evaluate(
                        plan.task(), plan.workspace(), plan.baseline());
                if (result == null) {
                    throw new IllegalStateException("benchmark grader returned no result");
                }
                results.add(result);
                record("workspace", result, "passed", "failed", startedAt);
            } catch (RuntimeException failure) {
                log.warn("Benchmark grader failed, taskId={}, ruleId={}, error={}",
                        plan.task().id(), rule.id(), LogExceptionSanitizer.sanitizeMessage(failure));
                GenerationBenchmarkRuleResult failed = GenerationBenchmarkRuleResult.failed(
                        rule.id(), rule.dimension(), "grader_execution_failed");
                results.add(failed);
                record("workspace", failed, "error", "error", startedAt);
            }
        }
        evaluateResponseRules(plan.task(), responseText, results);
        evaluateRuntimeGraders(plan, results);
        return new GenerationBenchmarkQualityEvidence(results);
    }

    private void evaluateResponseRules(
            GenerationBenchmarkTask task,
            String responseText,
            List<GenerationBenchmarkRuleResult> results
    ) {
        for (GenerationBenchmarkResponseRule rule : responseRules) {
            if (!rule.supports(task)) {
                continue;
            }
            long startedAt = System.nanoTime();
            try {
                GenerationBenchmarkRuleResult result = rule.evaluate(task, responseText);
                if (result == null) {
                    throw new IllegalStateException("Benchmark 响应评分器未返回结果");
                }
                results.add(result);
                record("response", result, "passed", "failed", startedAt);
            } catch (RuntimeException failure) {
                log.warn("Benchmark 响应评分失败，taskId={}, ruleId={}, error={}",
                        task.id(), rule.id(), LogExceptionSanitizer.sanitizeMessage(failure));
                GenerationBenchmarkRuleResult failed = GenerationBenchmarkRuleResult.failed(
                        rule.id(), rule.dimension(), "grader_execution_failed");
                results.add(failed);
                record("response", failed, "error", "error", startedAt);
            }
        }
    }

    private void evaluateRuntimeGraders(
            GenerationBenchmarkValidationPlan plan,
            List<GenerationBenchmarkRuleResult> results
    ) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (plan.runtimeGraders().isEmpty()) {
            return;
        }
        GenerationBenchmarkRuntimeContext context;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            context = new GenerationBenchmarkRuntimeContext(
                    plan.task(), plan.workspace(), plan.userId());
        } catch (RuntimeException failure) {
            for (GenerationBenchmarkRuntimeGrader grader : plan.runtimeGraders()) {
                for (GenerationBenchmarkQualityDimension dimension : dimensions(grader)) {
                    GenerationBenchmarkRuleResult failed = GenerationBenchmarkRuleResult.failed(
                            grader.id(), dimension, "runtime_context_invalid");
                    results.add(failed);
                    metricsCollector.record("runtime", dimension, "error", Duration.ZERO);
                }
            }
            return;
        }
        for (GenerationBenchmarkRuntimeGrader grader : plan.runtimeGraders()) {
            long startedAt = System.nanoTime();
            try {
                List<GenerationBenchmarkRuleResult> graderResults = normalizeRuntimeResults(
                        grader,
                        grader.evaluate(context)
                );
                results.addAll(graderResults);
                Duration duration = elapsed(startedAt);
                for (GenerationBenchmarkRuleResult result : graderResults) {
                    metricsCollector.record(
                            "runtime",
                            result.dimension(),
                            result.passed() ? "passed" : "failed",
                            duration
                    );
                }
            } catch (RuntimeException failure) {
                log.warn("Benchmark runtime grader failed, taskId={}, graderId={}, error={}",
                        plan.task().id(), grader.id(), LogExceptionSanitizer.sanitizeMessage(failure));
                Duration duration = elapsed(startedAt);
                for (GenerationBenchmarkQualityDimension dimension : dimensions(grader)) {
                    GenerationBenchmarkRuleResult failed = GenerationBenchmarkRuleResult.failed(
                            grader.id(), dimension, "grader_execution_failed");
                    results.add(failed);
                    metricsCollector.record("runtime", dimension, "error", duration);
                }
            }
        }
    }

    /** 规范化运行时{@code Results}。 */
    private List<GenerationBenchmarkRuleResult> normalizeRuntimeResults(
            GenerationBenchmarkRuntimeGrader grader,
            List<GenerationBenchmarkRuleResult> rawResults
    ) {
        List<GenerationBenchmarkRuleResult> safeResults = rawResults == null
                ? List.of()
                : rawResults.stream().filter(result -> result != null).toList();
        List<GenerationBenchmarkRuleResult> normalized = new ArrayList<>();
        for (GenerationBenchmarkQualityDimension dimension : dimensions(grader)) {
            List<GenerationBenchmarkRuleResult> matching = safeResults.stream()
                    .filter(result -> result.dimension() == dimension)
                    .toList();
            if (matching.isEmpty()) {
                normalized.add(GenerationBenchmarkRuleResult.failed(
                        grader.id(), dimension, "grader_result_missing"));
            } else {
                normalized.addAll(matching);
            }
        }
        return List.copyOf(normalized);
    }

    private List<GenerationBenchmarkQualityDimension> dimensions(
            GenerationBenchmarkRuntimeGrader grader
    ) {
        if (grader == null || grader.dimensions() == null) {
            return List.of(GenerationBenchmarkQualityDimension.RUNTIME);
        }
        List<GenerationBenchmarkQualityDimension> dimensions = grader.dimensions().stream()
                .filter(dimension -> dimension != null)
                .distinct()
                .toList();
        return dimensions.isEmpty()
                ? List.of(GenerationBenchmarkQualityDimension.RUNTIME)
                : dimensions;
    }

    private void record(
            String kind,
            GenerationBenchmarkRuleResult result,
            String passedStatus,
            String failedStatus,
            long startedAt
    ) {
        metricsCollector.record(
                kind,
                result.dimension(),
                result.passed() ? passedStatus : failedStatus,
                elapsed(startedAt)
        );
    }

    private Duration elapsed(long startedAt) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt));
    }
}
