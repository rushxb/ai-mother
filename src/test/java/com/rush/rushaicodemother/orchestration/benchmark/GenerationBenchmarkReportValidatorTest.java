package com.rush.rushaicodemother.orchestration.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationBenchmarkReportValidatorTest {

    private GenerationBenchmarkCatalog catalog;
    private GenerationBenchmarkRunner runner;
    private GenerationBenchmarkReportValidator validator;
    private List<GenerationBenchmarkRunResult> completeResults;

    @BeforeEach
    void setUp() {
        catalog = new GenerationBenchmarkCatalog(new ObjectMapper());
        runner = new GenerationBenchmarkRunner(catalog);
        validator = new GenerationBenchmarkReportValidator(catalog, runner);
        completeResults = catalog.tasks().stream()
                .map(task -> result(task.id(), task.mode()))
                .toList();
    }

    @Test
    void completeReportMustPass() {
        assertDoesNotThrow(() -> validator.validate(runner.summarize(completeResults)));
    }

    @Test
    void candidatePromptBundleMustNotBeReplacedByControlPlaneBundle() {
        GenerationBenchmarkReport report = runner.summarize(completeResults);

        assertDoesNotThrow(() -> validator.validate(withPromptBundle(
                report, "a".repeat(64))));
    }

    @Test
    void legacyReportSchemaMustBeRejected() {
        GenerationBenchmarkReport report = runner.summarize(completeResults);

        assertThrows(BusinessException.class, () -> validator.validate(
                withSchemaVersion(report, GenerationBenchmarkReport.CURRENT_SCHEMA_VERSION - 1)));
    }

    @Test
    void missingTaskMustBeRejected() {
        assertThrows(BusinessException.class, () -> validator.validate(
                runner.summarize(completeResults.subList(0, completeResults.size() - 1))));
    }

    @Test
    void duplicateTaskMustBeRejected() {
        List<GenerationBenchmarkRunResult> results = new ArrayList<>(completeResults);
        results.set(results.size() - 1, results.getFirst());

        assertThrows(BusinessException.class, () -> validator.validate(runner.summarize(results)));
    }

    @Test
    void unknownTaskMustBeRejected() {
        List<GenerationBenchmarkRunResult> results = new ArrayList<>(completeResults);
        GenerationBenchmarkRunResult last = results.getLast();
        results.set(results.size() - 1, result("unknown_task", last.mode()));

        assertThrows(BusinessException.class, () -> validator.validate(runner.summarize(results)));
    }

    @Test
    void modeMismatchMustBeRejected() {
        List<GenerationBenchmarkRunResult> results = new ArrayList<>(completeResults);
        GenerationBenchmarkRunResult first = results.getFirst();
        results.set(0, result(first.taskId(), "AGENT_EDIT"));

        assertThrows(BusinessException.class, () -> validator.validate(runner.summarize(results)));
    }

    @Test
    void forgedAggregateMustBeRejected() {
        GenerationBenchmarkReport report = runner.summarize(completeResults);

        assertThrows(BusinessException.class, () -> validator.validate(withTotalTasks(
                report, report.totalTasks() + 1)));
    }

    private GenerationBenchmarkRunResult result(String taskId, String mode) {
        return new GenerationBenchmarkRunResult(
                taskId, mode, true, true, 100, 1, 0, false, 0, "",
                0L, 0L, 0L, 100L, GenerationBenchmarkQualityEvidence.empty());
    }

    private GenerationBenchmarkReport withTotalTasks(GenerationBenchmarkReport report, int totalTasks) {
        return new GenerationBenchmarkReport(
                report.schemaVersion(),
                totalTasks,
                report.successCount(),
                report.buildPassedCount(),
                report.successRate(),
                report.buildPassRate(),
                report.averageDurationMs(),
                report.p50DurationMs(),
                report.p90DurationMs(),
                report.p99DurationMs(),
                report.aiCallCount(),
                report.toolCallCount(),
                report.fallbackCount(),
                report.repairRounds(),
                report.totalTokens(),
                report.totalCreditCost(),
                report.averageFirstTokenLatencyMs(),
                report.p90FirstTokenLatencyMs(),
                report.p99FirstTokenLatencyMs(),
                report.firstPreviewObservedCount(),
                report.firstPreviewObservationRate(),
                report.averageFirstPreviewLatencyMs(),
                report.p90FirstPreviewLatencyMs(),
                report.p99FirstPreviewLatencyMs(),
                report.promptBundleId(),
                report.modelFingerprint(),
                report.qualityStats(),
                report.modeStats(),
                report.results()
        );
    }

    private GenerationBenchmarkReport withPromptBundle(GenerationBenchmarkReport report,
                                                       String promptBundleId) {
        return new GenerationBenchmarkReport(
                report.schemaVersion(),
                report.totalTasks(),
                report.successCount(),
                report.buildPassedCount(),
                report.successRate(),
                report.buildPassRate(),
                report.averageDurationMs(),
                report.p50DurationMs(),
                report.p90DurationMs(),
                report.p99DurationMs(),
                report.aiCallCount(),
                report.toolCallCount(),
                report.fallbackCount(),
                report.repairRounds(),
                report.totalTokens(),
                report.totalCreditCost(),
                report.averageFirstTokenLatencyMs(),
                report.p90FirstTokenLatencyMs(),
                report.p99FirstTokenLatencyMs(),
                report.firstPreviewObservedCount(),
                report.firstPreviewObservationRate(),
                report.averageFirstPreviewLatencyMs(),
                report.p90FirstPreviewLatencyMs(),
                report.p99FirstPreviewLatencyMs(),
                promptBundleId,
                report.modelFingerprint(),
                report.qualityStats(),
                report.modeStats(),
                report.results()
        );
    }

    private GenerationBenchmarkReport withSchemaVersion(GenerationBenchmarkReport report,
                                                        int schemaVersion) {
        return new GenerationBenchmarkReport(
                schemaVersion,
                report.totalTasks(),
                report.successCount(),
                report.buildPassedCount(),
                report.successRate(),
                report.buildPassRate(),
                report.averageDurationMs(),
                report.p50DurationMs(),
                report.p90DurationMs(),
                report.p99DurationMs(),
                report.aiCallCount(),
                report.toolCallCount(),
                report.fallbackCount(),
                report.repairRounds(),
                report.totalTokens(),
                report.totalCreditCost(),
                report.averageFirstTokenLatencyMs(),
                report.p90FirstTokenLatencyMs(),
                report.p99FirstTokenLatencyMs(),
                report.firstPreviewObservedCount(),
                report.firstPreviewObservationRate(),
                report.averageFirstPreviewLatencyMs(),
                report.p90FirstPreviewLatencyMs(),
                report.p99FirstPreviewLatencyMs(),
                report.promptBundleId(),
                report.modelFingerprint(),
                report.qualityStats(),
                report.modeStats(),
                report.results()
        );
    }
}
