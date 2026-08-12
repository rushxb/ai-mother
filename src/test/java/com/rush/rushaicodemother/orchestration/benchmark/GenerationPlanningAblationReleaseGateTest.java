package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties;
import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationPlanningAblationReleaseGateTest {

    @Test
    void completeAblationMustAcceptSimplerCandidateOnlyWhenQualityHoldsAndEfficiencyImproves() {
        GenerationPlanningAblationReport report = ablation(
                source(GenerationPlanningVariant.NO_PLAN, 80, 900, 300_000, 30),
                source(GenerationPlanningVariant.COMPACT_PLAN, 70, 900, 300_000, 30),
                source(GenerationPlanningVariant.CURRENT_DAG, 100, 1_000, 320_000, 32)
        );

        GenerationPlanningAblationAssessment assessment = gate().assessPlanningCandidate(
                report, GenerationPlanningVariant.COMPACT_PLAN, GenerationPlanningVariant.CURRENT_DAG);

        assertTrue(assessment.passed());
    }

    @Test
    void qualityRegressionMustBlockCheaperCandidate() {
        GenerationBenchmarkReport compact = withSuccessRate(
                source(GenerationPlanningVariant.COMPACT_PLAN, 50, 800, 250_000, 25), 0.99);
        GenerationPlanningAblationAssessment assessment = gate().assessPlanningCandidate(
                ablation(
                        source(GenerationPlanningVariant.NO_PLAN, 40, 700, 200_000, 20),
                        compact,
                        source(GenerationPlanningVariant.CURRENT_DAG, 100, 1_000, 320_000, 32)
                ),
                GenerationPlanningVariant.COMPACT_PLAN,
                GenerationPlanningVariant.CURRENT_DAG
        );

        assertFalse(assessment.passed());
        assertTrue(assessment.violations().contains("planning_success_rate_regressed"));
    }

    @Test
    void candidateWithoutEfficiencyGainAndIncompleteExperimentMustBeRejected() {
        GenerationBenchmarkReport baseline = source(
                GenerationPlanningVariant.CURRENT_DAG, 100, 1_000, 320_000, 32);
        GenerationPlanningAblationAssessment assessment = gate().assessPlanningCandidate(
                ablation(
                        source(GenerationPlanningVariant.NO_PLAN, 100, 1_000, 320_000, 32),
                        source(GenerationPlanningVariant.COMPACT_PLAN, 100, 1_000, 320_000, 32),
                        baseline
                ),
                GenerationPlanningVariant.COMPACT_PLAN,
                GenerationPlanningVariant.CURRENT_DAG
        );

        assertFalse(assessment.passed());
        assertTrue(assessment.violations().contains("planning_efficiency_not_improved"));
        assertThrows(IllegalArgumentException.class, () -> GenerationPlanningAblationReport.from(
                Map.of(GenerationPlanningVariant.CURRENT_DAG, baseline)));
    }

    @Test
    void differentCatalogOrEfficiencyRegressionMustBeRejected() {
        GenerationBenchmarkReport baseline = source(
                GenerationPlanningVariant.CURRENT_DAG, 100, 1_000, 320_000, 32);
        GenerationBenchmarkReport differentCatalog = withFirstTaskId(
                source(GenerationPlanningVariant.COMPACT_PLAN, 90, 900, 300_000, 30),
                "different-task");

        GenerationPlanningAblationAssessment identityAssessment = gate().assessPlanningCandidate(
                ablation(
                        source(GenerationPlanningVariant.NO_PLAN, 80, 800, 280_000, 28),
                        differentCatalog,
                        baseline
                ),
                GenerationPlanningVariant.COMPACT_PLAN,
                GenerationPlanningVariant.CURRENT_DAG
        );
        GenerationPlanningAblationAssessment efficiencyAssessment = gate().assessPlanningCandidate(
                ablation(
                        source(GenerationPlanningVariant.NO_PLAN, 80, 800, 280_000, 28),
                        source(GenerationPlanningVariant.COMPACT_PLAN, 90, 1_100, 300_000, 30),
                        baseline
                ),
                GenerationPlanningVariant.COMPACT_PLAN,
                GenerationPlanningVariant.CURRENT_DAG
        );

        assertFalse(identityAssessment.passed());
        assertTrue(identityAssessment.violations().contains("planning_evidence_identity_mismatch"));
        assertFalse(efficiencyAssessment.passed());
        assertTrue(efficiencyAssessment.violations().contains("planning_efficiency_regressed"));
    }

    private GenerationBenchmarkReleaseGate gate() {
        return new GenerationBenchmarkReleaseGate(new GenerationBenchmarkReleaseProperties());
    }

    private GenerationPlanningAblationReport ablation(GenerationBenchmarkReport noPlan,
                                                       GenerationBenchmarkReport compact,
                                                       GenerationBenchmarkReport current) {
        EnumMap<GenerationPlanningVariant, GenerationBenchmarkReport> reports =
                new EnumMap<>(GenerationPlanningVariant.class);
        reports.put(GenerationPlanningVariant.NO_PLAN, noPlan);
        reports.put(GenerationPlanningVariant.COMPACT_PLAN, compact);
        reports.put(GenerationPlanningVariant.CURRENT_DAG, current);
        return GenerationPlanningAblationReport.from(reports);
    }

    private GenerationBenchmarkReport source(GenerationPlanningVariant variant,
                                              long preparationMs,
                                              long durationMs,
                                              long tokens,
                                              long creditCost) {
        List<GenerationBenchmarkRunResult> results = IntStream.range(0, 32)
                .mapToObj(index -> new GenerationBenchmarkRunResult(
                        "task-" + index,
                        "HEAVY_EXPERT",
                        true,
                        true,
                        durationMs,
                        1,
                        1,
                        false,
                        0,
                        "",
                        tokens / 32,
                        creditCost / 32,
                        1_000,
                        1_500L,
                        GenerationBenchmarkQualityEvidence.empty(),
                        "",
                        true,
                        variant,
                        preparationMs
                ))
                .toList();
        Map<String, GenerationBenchmarkReport.QualityStats> quality = quality(1.0);
        Map<String, GenerationBenchmarkReport.ModeStats> modes = Map.of(
                "HEAVY_EXPERT", new GenerationBenchmarkReport.ModeStats(
                        32, 32, 32, 1.0, 1.0,
                        durationMs, durationMs, durationMs, durationMs,
                        0, 32, 1.0, 1_500, 1_500, 1_500));
        return new GenerationBenchmarkReport(
                GenerationBenchmarkReport.CURRENT_SCHEMA_VERSION,
                32, 32, 32, 1.0, 1.0,
                durationMs, durationMs, durationMs, durationMs,
                32, 32, 0, 0, tokens, creditCost,
                1_000, 1_000, 1_000,
                32, 1.0, 1_500, 1_500, 1_500,
                "prompt-bundle-1", "model-fingerprint-1",
                quality, modes, results);
    }

    private GenerationBenchmarkReport withSuccessRate(GenerationBenchmarkReport source,
                                                       double successRate) {
        return new GenerationBenchmarkReport(
                source.schemaVersion(), source.totalTasks(), 31, source.buildPassedCount(),
                successRate, source.buildPassRate(), source.averageDurationMs(),
                source.p50DurationMs(), source.p90DurationMs(), source.p99DurationMs(),
                source.aiCallCount(), source.toolCallCount(), source.fallbackCount(),
                source.repairRounds(), source.totalTokens(), source.totalCreditCost(),
                source.averageFirstTokenLatencyMs(), source.p90FirstTokenLatencyMs(),
                source.p99FirstTokenLatencyMs(), source.firstPreviewObservedCount(),
                source.firstPreviewObservationRate(), source.averageFirstPreviewLatencyMs(),
                source.p90FirstPreviewLatencyMs(), source.p99FirstPreviewLatencyMs(),
                source.promptBundleId(), source.modelFingerprint(), source.qualityStats(),
                source.modeStats(), source.results());
    }

    private GenerationBenchmarkReport withFirstTaskId(GenerationBenchmarkReport source,
                                                       String taskId) {
        List<GenerationBenchmarkRunResult> results = new java.util.ArrayList<>(source.results());
        GenerationBenchmarkRunResult original = results.get(0);
        results.set(0, new GenerationBenchmarkRunResult(
                taskId, original.mode(), original.success(), original.buildPassed(),
                original.durationMs(), original.aiCallCount(), original.toolCallCount(),
                original.fallback(), original.repairRounds(), original.failureReason(),
                original.totalTokens(), original.creditCost(), original.firstTokenLatencyMs(),
                original.firstPreviewLatencyMs(), original.qualityEvidence(), original.expectedRoute(),
                original.routeAllowed(), original.planningVariant(), original.preparationDurationMs()));
        return new GenerationBenchmarkReport(
                source.schemaVersion(), source.totalTasks(), source.successCount(),
                source.buildPassedCount(), source.successRate(), source.buildPassRate(),
                source.averageDurationMs(), source.p50DurationMs(), source.p90DurationMs(),
                source.p99DurationMs(), source.aiCallCount(), source.toolCallCount(),
                source.fallbackCount(), source.repairRounds(), source.totalTokens(),
                source.totalCreditCost(), source.averageFirstTokenLatencyMs(),
                source.p90FirstTokenLatencyMs(), source.p99FirstTokenLatencyMs(),
                source.firstPreviewObservedCount(), source.firstPreviewObservationRate(),
                source.averageFirstPreviewLatencyMs(), source.p90FirstPreviewLatencyMs(),
                source.p99FirstPreviewLatencyMs(), source.promptBundleId(), source.modelFingerprint(),
                source.qualityStats(), source.modeStats(), results);
    }

    private Map<String, GenerationBenchmarkReport.QualityStats> quality(double passRate) {
        Map<String, GenerationBenchmarkReport.QualityStats> quality = new LinkedHashMap<>();
        for (String dimension : List.of(
                "structural", "functional", "diff_scope", "security", "runtime", "visual")) {
            quality.put(dimension, new GenerationBenchmarkReport.QualityStats(
                    32, (int) Math.round(32 * passRate), 1.0, passRate));
        }
        return quality;
    }
}
