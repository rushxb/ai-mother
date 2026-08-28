package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityEvidence;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRunResult;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidencePayload;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceProtocol;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceRecord;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationVerifiedBenchmarkEvidence;
import com.rush.rushaicodemother.orchestration.release.GenerationExecutionReleaseIdentity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationOfflineOnlineCorrelationServiceTest {

    private static final String EVIDENCE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String DATASET = "a".repeat(64);
    private static final String RUNTIME = "b".repeat(64);
    private static final String PROMPT = "c".repeat(64);
    private static final String MODEL = "d".repeat(64);
    private static final String GIT_COMMIT = "e".repeat(40);
    private static final String DECISION_VERSION = "routing-policy-v1";

    private final GenerationOfflineOnlineCorrelationService service =
            new GenerationOfflineOnlineCorrelationService();

    @Test
    void signedOfflineEvidenceMustExplainProductionFailureRepairAndLowRatingByRoute() {
        GenerationVerifiedBenchmarkEvidence evidence = evidence(List.of(
                result("agent-ok", "AGENT_EDIT", true, 0, true),
                result("agent-repair", "AGENT_EDIT", true, 2, false),
                result("agent-failed", "AGENT_EDIT", false, 1, false),
                result("heavy-unrelated", "HEAVY_EXPERT", false, 5, false)));
        GenerationScenarioBucketSummary candidate = candidateSummary(releaseFingerprint());

        GenerationOfflineOnlineCorrelation correlation = service.correlate(
                evidence, candidate, Instant.parse("2026-08-01T00:00:00Z"));

        assertEquals(EVIDENCE_ID, correlation.evidenceId());
        assertEquals(DATASET, correlation.datasetFingerprint());
        assertEquals("agent_edit", correlation.route());
        assertEquals(3, correlation.offlineTaskCount());
        assertEquals(1.0 / 3.0, correlation.deliveryFailure().offlineValue());
        assertEquals(0.1, correlation.deliveryFailure().onlineValue());
        assertEquals(1.0, correlation.averageRepairRounds().offlineValue());
        assertEquals(0.2, correlation.averageRepairRounds().onlineValue());
        assertEquals(2.0 / 3.0, correlation.qualityRisk().offlineValue());
        assertEquals(0.2, correlation.qualityRisk().onlineValue());
        assertEquals(2.0 / 3.0,
                correlation.offlineQualityFailureRates().get("structural"));
    }

    @Test
    void evidenceFromAnotherRuntimeReleaseMustNotBeLinkedToProductionCandidate() {
        GenerationScenarioBucketSummary candidate = candidateSummary("f".repeat(64));

        assertThrows(IllegalArgumentException.class, () -> service.correlate(
                evidence(List.of(result("agent-ok", "AGENT_EDIT", true, 0, true))),
                candidate,
                Instant.parse("2026-08-01T00:00:00Z")));
    }

    @Test
    void benchmarkMustPrecedeOnlineObservationWindowAndCoverTheSameRoute() {
        GenerationVerifiedBenchmarkEvidence evidence = evidence(List.of(
                result("heavy-only", "HEAVY_EXPERT", true, 0, true)));
        GenerationScenarioBucketSummary candidate = candidateSummary(releaseFingerprint());

        assertThrows(IllegalArgumentException.class, () -> service.correlate(
                evidence, candidate, Instant.parse("2026-07-01T00:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> service.correlate(
                evidence, candidate, Instant.parse("2026-08-01T00:00:00Z")));
    }

    private GenerationVerifiedBenchmarkEvidence evidence(List<GenerationBenchmarkRunResult> results) {
        GenerationBenchmarkEvidencePayload payload = new GenerationBenchmarkEvidencePayload(
                GenerationBenchmarkEvidenceProtocol.CURRENT_SIGNATURE_VERSION,
                GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE,
                "app-generation",
                "1".repeat(64),
                results.stream().mapToLong(GenerationBenchmarkRunResult::aiCallCount).sum(),
                DATASET,
                "generation-benchmark-graders-v1",
                RUNTIME,
                GIT_COMMIT,
                MODEL,
                PROMPT,
                "2".repeat(64),
                Instant.parse("2026-07-15T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"));
        GenerationBenchmarkEvidenceRecord record = new GenerationBenchmarkEvidenceRecord(
                EVIDENCE_ID, payload, "{}", true, List.of(), "3".repeat(64),
                Instant.parse("2026-07-15T00:01:00Z"));
        return new GenerationVerifiedBenchmarkEvidence(record, report(results));
    }

    private GenerationBenchmarkReport report(List<GenerationBenchmarkRunResult> results) {
        int successes = (int) results.stream().filter(GenerationBenchmarkRunResult::success).count();
        int buildPassed = (int) results.stream().filter(GenerationBenchmarkRunResult::buildPassed).count();
        int repairs = results.stream().mapToInt(GenerationBenchmarkRunResult::repairRounds).sum();
        return new GenerationBenchmarkReport(
                GenerationBenchmarkReport.CURRENT_SCHEMA_VERSION,
                results.size(), successes, buildPassed,
                rate(successes, results.size()), rate(buildPassed, results.size()),
                100, 100, 100, 100,
                results.size(), 0, 0, repairs,
                100L * results.size(), results.size(),
                10, 10, 10,
                results.size(), 1.0, 50, 50, 50,
                PROMPT, MODEL, Map.of(), Map.of(), results);
    }

    private GenerationBenchmarkRunResult result(String taskId,
                                                String mode,
                                                boolean success,
                                                int repairRounds,
                                                boolean qualityPassed) {
        GenerationBenchmarkRuleResult rule = qualityPassed
                ? GenerationBenchmarkRuleResult.passed(
                "structure", GenerationBenchmarkQualityDimension.STRUCTURAL)
                : GenerationBenchmarkRuleResult.failed(
                "structure", GenerationBenchmarkQualityDimension.STRUCTURAL, "invalid");
        return new GenerationBenchmarkRunResult(
                taskId, mode, success, success, 100, 1, 0, false, repairRounds,
                success ? "" : "generation_failed", 100, 1, 10, 50L,
                new GenerationBenchmarkQualityEvidence(List.of(rule)), mode, true,
                GenerationPlanningVariant.CURRENT_DAG, 10L);
    }

    private GenerationScenarioBucketSummary candidateSummary(String releaseIdentity) {
        return new GenerationScenarioBucketSummary(
                new GenerationScenarioBucketIdentity(
                        "4".repeat(64), "intent-profile-v1", DECISION_VERSION,
                        "agent_edit", releaseIdentity),
                new GenerationScenarioQualityMetrics(
                        40, 36, 40, 40, 36, 40, 8, 10, 2, 4.2),
                new GenerationScenarioLatencyMetrics(
                        40, 1_000.0, 2_000L, 40, 3_000.0, 4_000L),
                new GenerationScenarioCostMetrics(40, 1_000, 40, 100),
                new GenerationScenarioCapacityMetrics(40, 36, 1, 0));
    }

    private String releaseFingerprint() {
        return new GenerationExecutionReleaseIdentity(
                GIT_COMMIT, false, RUNTIME, PROMPT, MODEL, DECISION_VERSION)
                .releaseFingerprint();
    }

    private double rate(long count, long total) {
        return total == 0 ? 0.0 : (double) count / total;
    }
}
