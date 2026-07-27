package com.rush.rushaicodemother.orchestration.runtime.task.progress;

import java.time.Instant;

/** 保守的面向用户的进度估计；值是遥测得出的，从不保证执行。 */
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
