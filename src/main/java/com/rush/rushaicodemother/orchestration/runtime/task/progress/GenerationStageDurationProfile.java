package com.rush.rushaicodemother.orchestration.runtime.task.progress;

/** 一次可观察发电操作的历史最接近排名持续时间百分位数。 */
public record GenerationStageDurationProfile(
        String stage,
        String category,
        int sampleSize,
        long p50DurationMs,
        long p90DurationMs,
        long maxDurationMs
) {
}
