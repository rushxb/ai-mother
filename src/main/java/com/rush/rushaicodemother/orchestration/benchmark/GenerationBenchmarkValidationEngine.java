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

/** Applies composable fixture rules, captures the baseline and grades the resulting workspace. */
@Slf4j
@Component
public class GenerationBenchmarkValidationEngine {

    private final List<GenerationBenchmarkValidationRule> rules;
    private final List<GenerationBenchmarkRuntimeGrader> runtimeGraders;
    private final GenerationBenchmarkWorkspaceInspector inspector;
    private final GenerationBenchmarkGraderMetricsCollector metricsCollector;

    @Autowired
    public GenerationBenchmarkValidationEngine(
            List<GenerationBenchmarkValidationRule> rules,
            List<GenerationBenchmarkRuntimeGrader> runtimeGraders,
            GenerationBenchmarkWorkspaceInspector inspector,
            GenerationBenchmarkGraderMetricsCollector metricsCollector
    ) {
        this.rules = rules == null ? List.of() : rules.stream()
                .sorted(Comparator.comparingInt(GenerationBenchmarkValidationRule::order)
                        .thenComparing(GenerationBenchmarkValidationRule::id))
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
            GenerationBenchmarkWorkspaceInspector inspector
    ) {
        this(
                rules,
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
        evaluateRuntimeGraders(plan, results);
        return new GenerationBenchmarkQualityEvidence(results);
    }

    private void evaluateRuntimeGraders(
            GenerationBenchmarkValidationPlan plan,
            List<GenerationBenchmarkRuleResult> results
    ) {
        if (plan.runtimeGraders().isEmpty()) {
            return;
        }
        GenerationBenchmarkRuntimeContext context;
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
