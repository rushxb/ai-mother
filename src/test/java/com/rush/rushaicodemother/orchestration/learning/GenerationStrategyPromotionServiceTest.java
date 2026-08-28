package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationStrategyPromotionServiceTest {

    private static final String INTENT_SIGNATURE = "a".repeat(64);
    private static final String BASELINE_RELEASE = "b".repeat(64);
    private static final String CANDIDATE_RELEASE = "c".repeat(64);
    private static final Instant FROM = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void assessmentMustUseExactlyMatchedRuntimeFingerprintsFromRepository() {
        GenerationScenarioAttributionRepository repository =
                mock(GenerationScenarioAttributionRepository.class);
        GenerationScenarioBucketSummary baseline = summary(BASELINE_RELEASE, 38, 12_000L);
        GenerationScenarioBucketSummary candidate = summary(CANDIDATE_RELEASE, 39, 11_000L);
        when(repository.summarize(INTENT_SIGNATURE, FROM, TO, 500))
                .thenReturn(List.of(candidate, baseline));
        GenerationStrategyPromotionService service = service(repository);

        GenerationStrategyPromotionAssessment result = service.assess(
                new GenerationStrategyPromotionQuery(
                        INTENT_SIGNATURE, BASELINE_RELEASE, CANDIDATE_RELEASE, FROM, TO));

        assertTrue(result.passed());
        assertEquals(BASELINE_RELEASE, result.rollbackReleaseIdentity());
        verify(repository).summarize(INTENT_SIGNATURE, FROM, TO, 500);
    }

    @Test
    void ambiguousReleaseEvidenceMustFailClosedInsteadOfMergingPercentiles() {
        GenerationScenarioAttributionRepository repository =
                mock(GenerationScenarioAttributionRepository.class);
        GenerationScenarioBucketSummary baseline = summary(BASELINE_RELEASE, 38, 12_000L);
        GenerationScenarioBucketSummary firstCandidate = summary(CANDIDATE_RELEASE, 39, 11_000L);
        GenerationScenarioBucketSummary secondCandidate = summary(
                CANDIDATE_RELEASE, "light_edit", 39, 10_000L);
        when(repository.summarize(INTENT_SIGNATURE, FROM, TO, 500))
                .thenReturn(List.of(baseline, firstCandidate, secondCandidate));

        assertThrows(BusinessException.class, () -> service(repository).assess(
                new GenerationStrategyPromotionQuery(
                        INTENT_SIGNATURE, BASELINE_RELEASE, CANDIDATE_RELEASE, FROM, TO)));
    }

    @Test
    void invalidFingerprintsMustBeRejectedBeforeReadingProductionEvidence() {
        GenerationScenarioAttributionRepository repository =
                mock(GenerationScenarioAttributionRepository.class);

        assertThrows(IllegalArgumentException.class, () -> service(repository).assess(
                new GenerationStrategyPromotionQuery(
                        INTENT_SIGNATURE, "handwritten-v1", CANDIDATE_RELEASE, FROM, TO)));

        verify(repository, never()).summarize(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    private GenerationStrategyPromotionService service(
            GenerationScenarioAttributionRepository repository) {
        return new GenerationStrategyPromotionService(
                repository,
                new GenerationStrategyPromotionGate(new GenerationBenchmarkReleaseProperties()));
    }

    private GenerationScenarioBucketSummary summary(String releaseIdentity,
                                                      long successCount,
                                                      long p95DeliveredMs) {
        return summary(releaseIdentity, "agent_edit", successCount, p95DeliveredMs);
    }

    private GenerationScenarioBucketSummary summary(String releaseIdentity,
                                                      String route,
                                                      long successCount,
                                                      long p95DeliveredMs) {
        long totalTokens = CANDIDATE_RELEASE.equals(releaseIdentity) ? 390_000 : 400_000;
        long totalCredits = CANDIDATE_RELEASE.equals(releaseIdentity) ? 190 : 200;
        return new GenerationScenarioBucketSummary(
                new GenerationScenarioBucketIdentity(
                        INTENT_SIGNATURE, "intent-profile-v1", "routing-policy-v1",
                        route, releaseIdentity),
                new GenerationScenarioQualityMetrics(
                        40, successCount, 40, 40, 36, 40, 4, 10, 1, 4.5),
                new GenerationScenarioLatencyMetrics(
                        40, 2_000.0, 4_000L, 40, 6_000.0, p95DeliveredMs),
                new GenerationScenarioCostMetrics(40, totalTokens, 40, totalCredits),
                new GenerationScenarioCapacityMetrics(40, successCount, 1, 0));
    }
}
