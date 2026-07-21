package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.ai.prompt.PromptCatalogSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationBenchmarkCatalogTest {

    @Test
    void shouldExposeCreateAndEditBenchmarkTasks() {
        GenerationBenchmarkCatalog catalog = new GenerationBenchmarkCatalog();

        assertTrue(catalog.tasks().stream().anyMatch(task -> "CREATE".equals(task.mode())));
        assertTrue(catalog.tasks().stream().anyMatch(task -> "LIGHT_EDIT".equals(task.mode())));
        assertTrue(catalog.tasks().stream().anyMatch(task -> "AGENT_EDIT".equals(task.mode())));
        assertTrue(catalog.tasks().size() >= 12);
    }

    @Test
    void shouldSummarizeBenchmarkResultsWithModeStatsAndPercentiles() {
        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(new GenerationBenchmarkCatalog());
        List<GenerationBenchmarkRunResult> results = List.of(
                new GenerationBenchmarkRunResult("create-1", "CREATE", true, true, 100, 2, 0, false, 0,
                        "", 0, 0, 0, quality(true, true, false, false)),
                new GenerationBenchmarkRunResult("create-2", "CREATE", false, false, 300, 2, 0, true, 1,
                    "build_failed", 3000, 3, 900, quality(true, false, false, false)),
                new GenerationBenchmarkRunResult("edit-1", "AGENT_EDIT", true, true, 200, 1, 3, false, 1,
                        "", 2000, 2, 300, quality(true, true, true, true))
        );

        GenerationBenchmarkReport report = runner.summarize(results);

        assertEquals(3, report.totalTasks());
        assertEquals(2, report.successCount());
        assertEquals(2.0 / 3.0, report.successRate());
        assertEquals(200, report.averageDurationMs());
        assertEquals(200, report.p50DurationMs());
        assertEquals(300, report.p90DurationMs());
        assertEquals(300, report.p99DurationMs());
        assertEquals(1, report.fallbackCount());
        assertEquals(5, report.aiCallCount());
        assertEquals(3, report.toolCallCount());
        assertEquals(2, report.repairRounds());
        assertEquals(5000, report.totalTokens());
        assertEquals(5, report.totalCreditCost());
        assertEquals(600, report.averageFirstTokenLatencyMs());
        assertEquals(900, report.p90FirstTokenLatencyMs());
        assertEquals(2, report.modeStats().get("CREATE").totalTasks());
        assertEquals(0.5, report.modeStats().get("CREATE").successRate());
        assertEquals(3, report.qualityStats().get("structural").evaluatedCount());
        assertEquals(2.0 / 3.0, report.qualityStats().get("structural").passRate());
        assertEquals(3, report.qualityStats().get("functional").evaluatedCount());
        assertEquals(1, report.qualityStats().get("diff_scope").evaluatedCount());
        assertEquals(1.0, report.qualityStats().get("diff_scope").passRate());
        assertEquals("", report.promptBundleId());
    }

    @Test
    void releaseGateMustRejectInsufficientQualityEvidence() {
        com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties properties =
                new com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties();
        properties.setMinimumTaskCount(3);
        properties.setMinimumSuccessRate(0.90);
        properties.setMinimumBuildPassRate(0.80);
        GenerationBenchmarkReleaseGate gate = new GenerationBenchmarkReleaseGate(properties);
        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(new GenerationBenchmarkCatalog());
        GenerationBenchmarkReport report = runner.summarize(List.of(
                new GenerationBenchmarkRunResult("a", "CREATE", true, true, 100, 1, 0, false, 0, ""),
                new GenerationBenchmarkRunResult("b", "CREATE", false, false, 200, 1, 0, true, 1, "failed")
        ));

        GenerationBenchmarkReleaseAssessment assessment = gate.assess(report);

        assertTrue(!assessment.passed());
        assertTrue(assessment.violations().contains("task_count_below_minimum"));
        assertTrue(assessment.violations().contains("success_rate_below_minimum"));
        assertTrue(assessment.violations().contains("functional_evaluation_rate_below_minimum"));
        assertTrue(assessment.violations().contains("prompt_bundle_missing"));
    }

    @Test
    void benchmarkReportMustBindManagedPromptBundle() {
        String bundleId = "a".repeat(64);
        PromptCatalog promptCatalog = new PromptCatalog() {
            @Override
            public java.util.Optional<com.rush.rushaicodemother.ai.prompt.PromptSelection> select(
                    com.rush.rushaicodemother.ai.prompt.PromptRolloutSubject subject) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<com.rush.rushaicodemother.ai.prompt.PromptSelection> identify(
                    String promptContent) {
                return java.util.Optional.empty();
            }

            @Override
            public PromptCatalogSnapshot snapshot() {
                return new PromptCatalogSnapshot(bundleId, Map.of(
                        "codegen-vue-project",
                        new PromptCatalogSnapshot.PromptRelease("v1", "b".repeat(64), "", "", 0)
                ));
            }
        };
        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(
                new GenerationBenchmarkCatalog(), promptCatalog);

        assertEquals(bundleId, runner.summarize(List.of()).promptBundleId());
    }

    private GenerationBenchmarkQualityEvidence quality(boolean structuralEvaluated,
                                                         boolean structuralPassed,
                                                         boolean diffEvaluated,
                                                         boolean diffPassed) {
        java.util.ArrayList<GenerationBenchmarkRuleResult> results = new java.util.ArrayList<>();
        if (structuralEvaluated) {
            results.add(new GenerationBenchmarkRuleResult(
                    "structure",
                    GenerationBenchmarkQualityDimension.STRUCTURAL,
                    structuralPassed,
                    structuralPassed ? List.of() : List.of("failed"),
                    0
            ));
            results.add(new GenerationBenchmarkRuleResult(
                    "functional",
                    GenerationBenchmarkQualityDimension.FUNCTIONAL,
                    structuralPassed,
                    structuralPassed ? List.of() : List.of("failed"),
                    0
            ));
        }
        if (diffEvaluated) {
            results.add(new GenerationBenchmarkRuleResult(
                    "diff",
                    GenerationBenchmarkQualityDimension.DIFF_SCOPE,
                    diffPassed,
                    diffPassed ? List.of() : List.of("failed"),
                    1
            ));
        }
        return new GenerationBenchmarkQualityEvidence(results);
    }
}
