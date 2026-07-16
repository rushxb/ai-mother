package com.rush.rushaicodemother.orchestration.runtime.task.progress;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationTaskProgressEstimatorTest {

    private static final Instant NOW = Instant.parse("2026-07-16T08:00:00Z");

    @Test
    void sufficientRouteHistoryMustDriveEtaProgressStageStatsAndDeadlineRisk() {
        GenerationDurationProfileService profiles = mock(GenerationDurationProfileService.class);
        when(profiles.getProfile("heavy")).thenReturn(new GenerationDurationProfile(
                "heavy", 40, 1_000_000L, 1_500_000L, 1_800_000L,
                List.of(new GenerationStageDurationProfile(
                        "build", "build", 35, 120_000L, 240_000L, 300_000L)), NOW));
        GenerationTaskProgressEstimator estimator = estimator(profiles);

        GenerationTaskProgressEstimate estimate = estimator.estimate(
                "heavy", "running", NOW.minusSeconds(600), NOW.plusSeconds(700), "build");

        assertTrue(estimate.available());
        assertEquals("historical_route", estimate.basis());
        assertEquals("high", estimate.confidence());
        assertEquals(40, estimate.historicalTaskSampleSize());
        assertEquals(1_000_000L, estimate.estimatedTotalMs());
        assertEquals(400_000L, estimate.estimatedRemainingMs());
        assertEquals(900_000L, estimate.conservativeRemainingMs());
        assertEquals(60, estimate.progressPercent());
        assertEquals(120_000L, estimate.currentStageP50DurationMs());
        assertEquals(240_000L, estimate.currentStageP90DurationMs());
        assertTrue(estimate.deadlineRisk());
        assertEquals(-200_000L, estimate.deadlineSlackMs());
    }

    @Test
    void insufficientOrUnavailableHistoryMustUseConfiguredFallbackWithoutBreakingStatus() {
        GenerationDurationProfileService profiles = mock(GenerationDurationProfileService.class);
        when(profiles.getProfile("agent_edit")).thenThrow(new IllegalStateException("database password=secret"));
        GenerationTaskProgressEstimator estimator = estimator(profiles);

        GenerationTaskProgressEstimate estimate = estimator.estimate(
                "agent_edit", "running", NOW.minusSeconds(60), null, "agent");

        assertEquals("configured_fallback", estimate.basis());
        assertEquals("low", estimate.confidence());
        assertEquals(Duration.ofMinutes(20).toMillis(), estimate.estimatedTotalMs());
        assertFalse(estimate.deadlineRisk());
    }

    @Test
    void terminalTaskMustExposeActualElapsedAndOneHundredPercent() {
        GenerationTaskProgressEstimator estimator = estimator(mock(GenerationDurationProfileService.class));

        GenerationTaskProgressEstimate estimate = estimator.estimate(
                "heavy", "success", NOW.minusSeconds(30), NOW.plusSeconds(60), "completed");

        assertEquals("terminal_actual", estimate.basis());
        assertEquals(30_000L, estimate.estimatedTotalMs());
        assertEquals(0L, estimate.estimatedRemainingMs());
        assertEquals(100, estimate.progressPercent());
        assertFalse(estimate.deadlineRisk());
    }

    private GenerationTaskProgressEstimator estimator(GenerationDurationProfileService profiles) {
        GenerationTaskProgressProperties properties = new GenerationTaskProgressProperties();
        properties.setMinimumHistoricalSamples(8);
        properties.setHighConfidenceSamples(30);
        properties.setFallbackTotalDuration(Duration.ofMinutes(20));
        properties.setMaximumEstimatedDuration(Duration.ofHours(2));
        properties.setMinimumRunningRemaining(Duration.ofSeconds(5));
        properties.setFallbackP90Multiplier(1.5d);
        properties.setRunningProgressCap(95);
        return new GenerationTaskProgressEstimator(
                profiles, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
