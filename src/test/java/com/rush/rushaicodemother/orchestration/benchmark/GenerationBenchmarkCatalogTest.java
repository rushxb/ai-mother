package com.rush.rushaicodemother.orchestration.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.ai.prompt.PromptCatalogSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationBenchmarkCatalogTest {

    @Test
    void shouldExposeCreateAndEditBenchmarkTasks() {
        GenerationBenchmarkCatalog catalog = catalog();

        assertTrue(catalog.tasks().stream().anyMatch(task -> "CREATE".equals(task.mode())));
        assertTrue(catalog.tasks().stream().anyMatch(task -> "LIGHT_EDIT".equals(task.mode())));
        assertTrue(catalog.tasks().stream().anyMatch(task -> "AGENT_EDIT".equals(task.mode())));
        assertTrue(catalog.tasks().size() >= 32);
        assertEquals(catalog.tasks().size(), catalog.tasks().stream()
                .map(GenerationBenchmarkTask::id)
                .distinct()
                .count());
        assertCoverage(catalog.tasks(), GenerationBenchmarkTask::mode, Map.of(
                "CREATE", 10L,
                "LIGHT_EDIT", 6L,
                "AGENT_EDIT", 10L
        ));
        assertCoverage(catalog.tasks(), GenerationBenchmarkTask::codeGenType, Map.of(
                "vue_project", 12L,
                "backend_project", 5L,
                "full_stack_project", 5L
        ));
        Set<GenerationBenchmarkDifficulty> difficulties = catalog.tasks().stream()
                .map(GenerationBenchmarkTask::difficulty)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                GenerationBenchmarkDifficulty.EASY,
                GenerationBenchmarkDifficulty.MEDIUM,
                GenerationBenchmarkDifficulty.HARD
        ), difficulties);
        assertTrue(catalog.tasks().stream().map(GenerationBenchmarkTask::scenario).distinct().count() >= 10);
        assertTrue(catalog.tasks().stream().filter(task -> !task.sourceAssertions().isEmpty()).count() >= 14);
        assertTrue(catalog.tasks().stream().allMatch(task -> task.requiredQualityDimensions()
                .contains(GenerationBenchmarkQualityDimension.RUNTIME)));
        assertTrue(catalog.tasks().stream()
                .filter(task -> !"backend_project".equals(task.codeGenType()))
                .allMatch(task -> task.requiredQualityDimensions()
                        .contains(GenerationBenchmarkQualityDimension.VISUAL)));
        assertTrue(catalog.tasks().stream()
                .filter(task -> "backend_project".equals(task.codeGenType()))
                .noneMatch(task -> task.requiredQualityDimensions()
                        .contains(GenerationBenchmarkQualityDimension.VISUAL)));
    }

    @Test
    void datasetMustRejectRequiredQualityDimensionDowngrade() {
        GenerationBenchmarkCatalog catalog = catalog();
        GenerationBenchmarkDataset dataset = catalog.dataset();
        int editIndex = -1;
        for (int index = 0; index < dataset.tasks().size(); index++) {
            if (!"CREATE".equals(dataset.tasks().get(index).mode())) {
                editIndex = index;
                break;
            }
        }
        GenerationBenchmarkTask task = dataset.tasks().get(editIndex);
        List<GenerationBenchmarkQualityDimension> dimensions = task.requiredQualityDimensions().stream()
                .filter(dimension -> dimension != GenerationBenchmarkQualityDimension.FUNCTIONAL)
                .toList();
        List<GenerationBenchmarkTask> tasks = new java.util.ArrayList<>(dataset.tasks());
        tasks.set(editIndex, new GenerationBenchmarkTask(
                task.id(),
                task.mode(),
                task.codeGenType(),
                task.prompt(),
                task.expectedValidation(),
                task.scenario(),
                task.difficulty(),
                task.capabilities(),
                dimensions,
                task.fixtureFiles(),
                task.sourceAssertions()
        ));

        assertThrows(IllegalStateException.class, () -> catalog.validate(new GenerationBenchmarkDataset(
                dataset.schemaVersion(), dataset.datasetId(), dataset.version(), tasks)));
    }

    @Test
    void datasetMustRejectBackendRuntimeAndFullStackVisualDowngrade() {
        GenerationBenchmarkCatalog catalog = catalog();
        GenerationBenchmarkDataset dataset = catalog.dataset();
        assertDimensionCannotBeRemoved(
                catalog,
                dataset,
                "backend_project",
                GenerationBenchmarkQualityDimension.RUNTIME
        );
        assertDimensionCannotBeRemoved(
                catalog,
                dataset,
                "full_stack_project",
                GenerationBenchmarkQualityDimension.VISUAL
        );
    }

    @Test
    void shouldSummarizeBenchmarkResultsWithModeStatsAndPercentiles() {
        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(catalog());
        List<GenerationBenchmarkRunResult> results = List.of(
                new GenerationBenchmarkRunResult("create-1", "CREATE", true, true, 100, 2, 0, false, 0,
                        "", 0, 0, 0, 80L, quality(true, true, false, false)),
                new GenerationBenchmarkRunResult("create-2", "CREATE", false, false, 300, 2, 0, true, 1,
                    "build_failed", 3000, 3, 900, null, quality(true, false, false, false)),
                new GenerationBenchmarkRunResult("edit-1", "AGENT_EDIT", true, true, 200, 1, 3, false, 1,
                        "", 2000, 2, 300, 150L, quality(true, true, true, true))
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
        assertEquals(2, report.deliveryEconomics().successfulDeliveryCount());
        assertEquals(2500.0, report.deliveryEconomics().providerTokensPerSuccessfulDelivery());
        assertEquals(2.5, report.deliveryEconomics().creditCostPerSuccessfulDelivery());
        assertEquals(600, report.averageFirstTokenLatencyMs());
        assertEquals(900, report.p90FirstTokenLatencyMs());
        assertEquals(900, report.p99FirstTokenLatencyMs());
        assertEquals(2, report.firstPreviewObservedCount());
        assertEquals(2.0 / 3.0, report.firstPreviewObservationRate());
        assertEquals(115, report.averageFirstPreviewLatencyMs());
        assertEquals(150, report.p90FirstPreviewLatencyMs());
        assertEquals(150, report.p99FirstPreviewLatencyMs());
        assertEquals(2, report.modeStats().get("CREATE").totalTasks());
        assertEquals(0.5, report.modeStats().get("CREATE").successRate());
        assertEquals(0.5, report.modeStats().get("CREATE").firstPreviewObservationRate());
        assertEquals(3, report.qualityStats().get("structural").evaluatedCount());
        assertEquals(2.0 / 3.0, report.qualityStats().get("structural").passRate());
        assertEquals(3, report.qualityStats().get("functional").evaluatedCount());
        assertEquals(1, report.qualityStats().get("diff_scope").evaluatedCount());
        assertEquals(1.0, report.qualityStats().get("diff_scope").passRate());
        assertEquals("", report.promptBundleId());
    }

    @Test
    void benchmarkReportMustExposeExpectedToActualRouteConfusionMatrix() {
        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(catalog());
        List<GenerationBenchmarkRunResult> results = List.of(
                routeResult("create-correct", "CREATE", "CREATE", true),
                routeResult("create-escalated", "AGENT_EDIT", "CREATE", false),
                routeResult("edit-degraded", "LIGHT_EDIT", "AGENT_EDIT", false)
        );

        GenerationBenchmarkReport report = runner.summarize(results);

        assertEquals(Map.of(
                        "CREATE", Map.of("CREATE", 1, "AGENT_EDIT", 1),
                        "AGENT_EDIT", Map.of("LIGHT_EDIT", 1)),
                report.routeStats().confusionMatrix());
    }

    @Test
    void qualityEvaluationRateMustUseOnlyTasksRequiringTheDimension() {
        GenerationBenchmarkCatalog catalog = catalog();
        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(catalog);
        GenerationBenchmarkTask vueTask = catalog.tasks().stream()
                .filter(task -> "vue_project".equals(task.codeGenType()))
                .filter(task -> task.requiredQualityDimensions()
                        .contains(GenerationBenchmarkQualityDimension.RUNTIME))
                .findFirst()
                .orElseThrow();
        GenerationBenchmarkTask backendTask = catalog.tasks().stream()
                .filter(task -> "backend_project".equals(task.codeGenType()))
                .findFirst()
                .orElseThrow();
        GenerationBenchmarkQualityEvidence browserEvidence = new GenerationBenchmarkQualityEvidence(List.of(
                GenerationBenchmarkRuleResult.passed(
                        "runtime", GenerationBenchmarkQualityDimension.RUNTIME),
                GenerationBenchmarkRuleResult.passed(
                        "visual", GenerationBenchmarkQualityDimension.VISUAL)
        ));
        GenerationBenchmarkQualityEvidence backendEvidence = new GenerationBenchmarkQualityEvidence(List.of(
                GenerationBenchmarkRuleResult.passed(
                        "backend_runtime", GenerationBenchmarkQualityDimension.RUNTIME)
        ));

        GenerationBenchmarkReport report = runner.summarize(List.of(
                new GenerationBenchmarkRunResult(
                        vueTask.id(), vueTask.mode(), true, true, 100, 1, 0,
                        false, 0, "", 0, 0, 0, 100L, browserEvidence),
                new GenerationBenchmarkRunResult(
                        backendTask.id(), backendTask.mode(), true, true, 100, 1, 0,
                        false, 0, "", 0, 0, 0, 100L, backendEvidence)
        ));

        assertEquals(2, report.qualityStats().get("runtime").evaluatedCount());
        assertEquals(1.0, report.qualityStats().get("runtime").evaluationRate());
        assertEquals(1, report.qualityStats().get("visual").evaluatedCount());
        assertEquals(1.0, report.qualityStats().get("visual").evaluationRate());
    }

    private GenerationBenchmarkRunResult routeResult(String taskId,
                                                      String actualRoute,
                                                      String expectedRoute,
                                                      boolean routeAllowed) {
        return new GenerationBenchmarkRunResult(
                taskId, actualRoute, true, true, 100, 1, 0,
                false, 0, "", 100, 1, 50, 80L,
                GenerationBenchmarkQualityEvidence.empty(), expectedRoute, routeAllowed);
    }

    @Test
    void releaseGateMustRejectInsufficientQualityEvidence() {
        com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties properties =
                new com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties();
        properties.setMinimumTaskCount(3);
        properties.setMinimumSuccessRate(0.90);
        properties.setMinimumBuildPassRate(0.80);
        GenerationBenchmarkReleaseGate gate = new GenerationBenchmarkReleaseGate(properties);
        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(catalog());
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
            public java.util.Optional<com.rush.rushaicodemother.ai.prompt.PromptSelection> selectByKey(
                    String promptKey, String cohortKey) {
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
                catalog(), promptCatalog);

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

    private GenerationBenchmarkCatalog catalog() {
        return new GenerationBenchmarkCatalog(new ObjectMapper());
    }

    private void assertDimensionCannotBeRemoved(
            GenerationBenchmarkCatalog catalog,
            GenerationBenchmarkDataset dataset,
            String codeGenType,
            GenerationBenchmarkQualityDimension dimension
    ) {
        int taskIndex = -1;
        for (int index = 0; index < dataset.tasks().size(); index++) {
            if (codeGenType.equals(dataset.tasks().get(index).codeGenType())) {
                taskIndex = index;
                break;
            }
        }
        GenerationBenchmarkTask task = dataset.tasks().get(taskIndex);
        List<GenerationBenchmarkQualityDimension> dimensions = task.requiredQualityDimensions().stream()
                .filter(candidate -> candidate != dimension)
                .toList();
        List<GenerationBenchmarkTask> tasks = new java.util.ArrayList<>(dataset.tasks());
        tasks.set(taskIndex, new GenerationBenchmarkTask(
                task.id(),
                task.mode(),
                task.codeGenType(),
                task.prompt(),
                task.expectedValidation(),
                task.scenario(),
                task.difficulty(),
                task.capabilities(),
                dimensions,
                task.fixtureFiles(),
                task.sourceAssertions()
        ));

        assertThrows(IllegalStateException.class, () -> catalog.validate(new GenerationBenchmarkDataset(
                dataset.schemaVersion(), dataset.datasetId(), dataset.version(), tasks)));
    }

    private void assertCoverage(List<GenerationBenchmarkTask> tasks,
                                java.util.function.Function<GenerationBenchmarkTask, String> classifier,
                                Map<String, Long> minimums) {
        Map<String, Long> counts = tasks.stream().collect(Collectors.groupingBy(
                classifier,
                Collectors.counting()
        ));
        minimums.forEach((key, minimum) -> assertTrue(counts.getOrDefault(key, 0L) >= minimum));
    }
}
