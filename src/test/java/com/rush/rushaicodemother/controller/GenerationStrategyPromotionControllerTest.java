package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioAttributionRepository;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioBucketIdentity;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioBucketSummary;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioCapacityMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioCostMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioLatencyMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioQualityMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationOfflineOnlineCorrelation;
import com.rush.rushaicodemother.orchestration.learning.GenerationOfflineOnlineCorrelationService;
import com.rush.rushaicodemother.orchestration.learning.GenerationStrategyPromotionGate;
import com.rush.rushaicodemother.orchestration.learning.GenerationStrategyPromotionService;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationReleaseEvidenceVerifier;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationVerifiedBenchmarkEvidence;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class GenerationStrategyPromotionControllerTest {

    private static final String INTENT_SIGNATURE = "a".repeat(64);
    private static final String BASELINE_RELEASE = "b".repeat(64);
    private static final String CANDIDATE_RELEASE = "c".repeat(64);
    private static final String EVIDENCE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final Instant FROM = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void administratorCanInspectEvidenceAndRollbackTargetBeforePromotion() {
        GenerationScenarioAttributionRepository repository =
                mock(GenerationScenarioAttributionRepository.class);
        when(repository.summarize(INTENT_SIGNATURE, FROM, TO, 500)).thenReturn(List.of(
                summary(BASELINE_RELEASE, 38, 12_000L, 400_000L),
                summary(CANDIDATE_RELEASE, 39, 11_000L, 390_000L)));
        GenerationReleaseEvidenceVerifier evidenceVerifier =
                mock(GenerationReleaseEvidenceVerifier.class);
        GenerationOfflineOnlineCorrelationService correlationService =
                mock(GenerationOfflineOnlineCorrelationService.class);
        when(evidenceVerifier.requirePassed(EVIDENCE_ID))
                .thenReturn(mock(GenerationVerifiedBenchmarkEvidence.class));
        when(correlationService.correlate(any(), any(), any())).thenReturn(correlation());
        GenerationStrategyPromotionService promotionService = new GenerationStrategyPromotionService(
                repository,
                new GenerationStrategyPromotionGate(new GenerationBenchmarkReleaseProperties()),
                evidenceVerifier,
                correlationService);
        GenerationPerformanceController controller = new GenerationPerformanceController(
                mock(com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService.class),
                mock(com.rush.rushaicodemother.monitor.span.GenerationSpanQueryService.class),
                mock(com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationProfileService.class),
                mock(com.rush.rushaicodemother.monitor.latency.GenerationTaskLatencyLedgerService.class),
                mock(com.rush.rushaicodemother.monitor.latency.GenerationRouteLatencySegmentService.class),
                promotionService);

        var response = controller.assessStrategyPromotion(
                INTENT_SIGNATURE, BASELINE_RELEASE, CANDIDATE_RELEASE,
                EVIDENCE_ID, FROM, TO);

        assertEquals(0, response.getCode());
        assertTrue(response.getData().passed());
        assertEquals(BASELINE_RELEASE, response.getData().rollbackReleaseIdentity());
        assertEquals(40, response.getData().baseline().quality().taskCount());
        assertEquals(12_000L, response.getData().baseline().latency().p95DeliveredMs());
        assertEquals(9_750.0, response.getData().candidate().cost().averageProviderTokens());
        assertEquals(10_000.0,
                response.getData().candidate().cost().providerTokensPerSuccessfulDelivery());
        assertEquals(1.0,
                response.getData().candidate().capacity().physicalModelCallsPerSuccessfulDelivery());
        assertEquals(0.0, response.getData().candidate().capacity().capacityFailureRate());
        assertEquals(EVIDENCE_ID, response.getData().correlation().evidenceId());
        assertEquals("benchmark_delivery_failure_rate",
                response.getData().correlation().deliveryFailure().offlineMetric());
    }

    @Test
    void strategyPromotionAssessmentEndpointMustRemainAdministratorOnly() throws NoSuchMethodException {
        Method method = GenerationPerformanceController.class.getMethod(
                "assessStrategyPromotion",
                String.class, String.class, String.class, String.class,
                Instant.class, Instant.class);

        AuthCheck authCheck = method.getAnnotation(AuthCheck.class);

        assertNotNull(authCheck);
        assertEquals(UserConstant.ADMIN_ROLE, authCheck.mustRole());
    }

    private GenerationScenarioBucketSummary summary(String releaseIdentity,
                                                      long successCount,
                                                      long p95DeliveredMs,
                                                      long totalProviderTokens) {
        return new GenerationScenarioBucketSummary(
                new GenerationScenarioBucketIdentity(
                        INTENT_SIGNATURE, "intent-profile-v1", "routing-policy-v1",
                        "agent_edit", releaseIdentity),
                new GenerationScenarioQualityMetrics(
                        40, successCount, 40, 40, 36, 40, 4, 10, 1, 4.5),
                new GenerationScenarioLatencyMetrics(
                        40, 2_000.0, 4_000L, 40, 6_000.0, p95DeliveredMs),
                new GenerationScenarioCostMetrics(
                        40, totalProviderTokens, 40,
                        CANDIDATE_RELEASE.equals(releaseIdentity) ? 190 : 200),
                new GenerationScenarioCapacityMetrics(40, successCount, 1, 0));
    }

    private GenerationOfflineOnlineCorrelation correlation() {
        GenerationOfflineOnlineCorrelation.MetricComparison failure =
                new GenerationOfflineOnlineCorrelation.MetricComparison(
                        "benchmark_delivery_failure_rate", 40, 0.05,
                        "production_delivery_failure_rate", 40, 0.025);
        GenerationOfflineOnlineCorrelation.MetricComparison repair =
                new GenerationOfflineOnlineCorrelation.MetricComparison(
                        "benchmark_average_repair_rounds", 40, 0.2,
                        "production_average_repair_rounds", 40, 0.1);
        GenerationOfflineOnlineCorrelation.MetricComparison quality =
                new GenerationOfflineOnlineCorrelation.MetricComparison(
                        "benchmark_quality_failure_rate", 40, 0.1,
                        "production_low_rating_rate", 10, 0.1);
        return new GenerationOfflineOnlineCorrelation(
                EVIDENCE_ID,
                "d".repeat(64),
                Instant.parse("2026-06-30T00:00:00Z"),
                "agent_edit",
                40,
                failure,
                repair,
                quality,
                java.util.Map.of("structural", 0.1));
    }
}
