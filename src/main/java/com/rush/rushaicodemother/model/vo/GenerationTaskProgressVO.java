package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimate;

import java.time.Instant;

/** 公共预计到达时间视图。估计值是遥测得出的范围，而不是完成保证。 */
public record GenerationTaskProgressVO(
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
    public static GenerationTaskProgressVO from(GenerationTaskProgressEstimate estimate) {
        if (estimate == null) {
            return null;
        }
        return new GenerationTaskProgressVO(
                estimate.available(), estimate.elapsedMs(), estimate.estimatedTotalMs(),
                estimate.estimatedRemainingMs(), estimate.conservativeRemainingMs(),
                estimate.estimatedCompletionAt(), estimate.conservativeCompletionAt(),
                estimate.progressPercent(), estimate.confidence(), estimate.basis(),
                estimate.historicalTaskSampleSize(), estimate.currentStageP50DurationMs(),
                estimate.currentStageP90DurationMs(), estimate.currentStageSampleSize(),
                estimate.deadlineRisk(), estimate.deadlineSlackMs(), estimate.computedAt());
    }
}
