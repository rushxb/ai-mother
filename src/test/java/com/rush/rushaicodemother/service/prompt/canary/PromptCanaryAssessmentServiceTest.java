package com.rush.rushaicodemother.service.prompt.canary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioBucketIdentity;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioBucketSummary;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioCapacityMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioCostMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioLatencyMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioQualityMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationStrategyPromotionGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptCanaryAssessmentServiceTest {

    private static final String STABLE_HASH = "a".repeat(64);
    private static final String CANARY_HASH = "b".repeat(64);
    private static final String BUNDLE_ID = "c".repeat(64);
    private static final Instant WINDOW_START = Instant.parse("2026-08-28T08:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-08-28T09:00:00Z");

    private StubObservationRepository observationRepository;
    private RecordingAssessmentStore assessmentStore;
    private PromptCanaryAssessmentService service;

    @BeforeEach
    void setUp() {
        GenerationBenchmarkReleaseProperties properties = new GenerationBenchmarkReleaseProperties();
        properties.setMinimumTaskCount(5);
        observationRepository = new StubObservationRepository();
        assessmentStore = new RecordingAssessmentStore();
        service = new PromptCanaryAssessmentService(
                observationRepository,
                assessmentStore,
                new GenerationStrategyPromotionGate(properties),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(WINDOW_END, ZoneOffset.UTC),
                () -> "550e8400-e29b-41d4-a716-446655440000"
        );
    }

    @Test
    void insufficientSamplesMustRemainObservingWithoutPretendingHealthy() {
        observationRepository.observation = observation(summary("stable", STABLE_HASH,
                4, 4, 4, 0, 4, 0, 100, 4, 400, 4, 4, 4),
                summary("canary", CANARY_HASH,
                        4, 4, 4, 0, 4, 0, 90, 4, 360, 4, 4, 4));

        PromptCanaryAssessment assessment = service.assessAndPersist(request());

        assertEquals(PromptCanaryDecision.OBSERVING, assessment.decision());
        assertTrue(assessment.violations().contains("baseline_task_count_below_minimum"));
        assertEquals(assessment, assessmentStore.saved);
    }

    @Test
    void comparableRegressionMustRequireRollback() {
        observationRepository.observation = observation(summary("stable", STABLE_HASH,
                10, 10, 5, 0, 10, 0, 100, 10, 1_000, 10, 10, 10),
                summary("canary", CANARY_HASH,
                        10, 7, 5, 2, 10, 8, 180, 10, 2_000, 10, 20, 10));

        PromptCanaryAssessment assessment = service.assessAndPersist(request());

        assertEquals(PromptCanaryDecision.ROLLBACK_REQUIRED, assessment.decision());
        assertTrue(assessment.violations().contains("success_rate_regressed"));
        assertTrue(assessment.violations().contains("delivered_p95_regressed"));
        assertTrue(assessment.violations().contains("provider_tokens_per_success_regressed"));
    }

    @Test
    void observedImprovementMustBecomePromotable() {
        observationRepository.observation = observation(summary("stable", STABLE_HASH,
                10, 10, 5, 0, 10, 3, 120, 10, 1_200, 10, 10, 10),
                summary("canary", CANARY_HASH,
                        10, 10, 5, 0, 10, 0, 90, 10, 900, 10, 10, 10));

        PromptCanaryAssessment assessment = service.assessAndPersist(request());

        assertEquals(PromptCanaryDecision.PROMOTABLE, assessment.decision());
        assertTrue(assessment.violations().isEmpty());
        assertEquals(64, assessment.evidenceHash().length());
        assertTrue(assessment.evidenceJson().contains("\"bundleId\":\"" + BUNDLE_ID + "\""));
    }

    @Test
    void inconsistentAttributionMustBeInvalidAndFailClosed() {
        observationRepository.observation = new PromptCanaryObservation(
                summary("stable", STABLE_HASH,
                        10, 10, 5, 0, 10, 0, 100, 10, 1_000, 10, 10, 10),
                summary("canary", CANARY_HASH,
                        10, 10, 5, 0, 10, 0, 90, 10, 900, 10, 10, 10),
                1,
                2
        );

        PromptCanaryAssessment assessment = service.assessAndPersist(request());

        assertEquals(PromptCanaryDecision.INVALID, assessment.decision());
        assertEquals(List.of("prompt_attribution_inconsistent"), assessment.violations());
    }

    private PromptCanaryObservation observation(GenerationScenarioBucketSummary stable,
                                                GenerationScenarioBucketSummary canary) {
        return new PromptCanaryObservation(stable, canary, 0, 0);
    }

    private GenerationScenarioBucketSummary summary(
            String channel,
            String releaseIdentity,
            long taskCount,
            long successCount,
            long feedbackCount,
            long lowRatingCount,
            long repairObservedCount,
            long totalRepairRounds,
            long p95DurationMs,
            long providerObservedCount,
            long totalProviderTokens,
            long creditObservedCount,
            long totalCreditCost,
            long capacityObservedCount
    ) {
        return new GenerationScenarioBucketSummary(
                new GenerationScenarioBucketIdentity(
                        "d".repeat(64), "prompt-canary-v1", "7", channel, releaseIdentity),
                new GenerationScenarioQualityMetrics(
                        taskCount, successCount, taskCount, taskCount, successCount,
                        repairObservedCount, totalRepairRounds, feedbackCount, lowRatingCount,
                        feedbackCount == 0 ? null : lowRatingCount == 0 ? 5.0 : 3.0),
                new GenerationScenarioLatencyMetrics(
                        taskCount, 50.0, 60L,
                        taskCount, (double) p95DurationMs, p95DurationMs),
                new GenerationScenarioCostMetrics(
                        providerObservedCount, totalProviderTokens,
                        creditObservedCount, totalCreditCost),
                new GenerationScenarioCapacityMetrics(
                        capacityObservedCount, taskCount, taskCount == 0 ? 0 : 1, 0)
        );
    }

    private PromptCanaryEvaluationRequest request() {
        return new PromptCanaryEvaluationRequest(
                "codegen-vue-project", 7L, 9L, BUNDLE_ID,
                "v1", STABLE_HASH, "v2", CANARY_HASH,
                WINDOW_START, WINDOW_END
        );
    }

    private static final class StubObservationRepository
            implements PromptCanaryObservationRepository {
        private PromptCanaryObservation observation;

        @Override
        public PromptCanaryObservation observe(PromptCanaryEvaluationRequest request) {
            return observation;
        }
    }

    private static final class RecordingAssessmentStore implements PromptCanaryAssessmentStore {
        private PromptCanaryAssessment saved;

        @Override
        public void save(PromptCanaryAssessment assessment) {
            saved = assessment;
        }
    }
}
