package com.rush.rushaicodemother.orchestration.runtime.task.progress;

import java.time.Instant;

/** Conservative user-facing progress estimate; values are telemetry-derived, never execution guarantees. */
public record GenerationTaskProgressEstimate(
        boolean available,
        long elapsedMs,
        Long estimatedTotalMs,
        Long estimatedRemainingMs,
        Long conservativeRemainingMs,
        Instant estimatedCompletionAt,
        Instant conservativeCompletionAt,
        Integer progressPercent,
        String confidence,
        String basis,
        int historicalTaskSampleSize,
        Long currentStageP50DurationMs,
        Long currentStageP90DurationMs,
        Integer currentStageSampleSize,
        boolean deadlineRisk,
        Long deadlineSlackMs,
        Instant computedAt
) {
    public static GenerationTaskProgressEstimate unavailable(long elapsedMs, Instant computedAt) {
        return new GenerationTaskProgressEstimate(
                false, elapsedMs, null, null, null, null, null, null,
                "unavailable", "insufficient_context", 0,
                null, null, null, false, null, computedAt);
    }
}
