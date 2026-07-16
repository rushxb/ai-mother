package com.rush.rushaicodemother.orchestration.runtime.task.progress;

/** Historical nearest-rank duration percentiles for one observable generation operation. */
public record GenerationStageDurationProfile(
        String stage,
        String category,
        int sampleSize,
        long p50DurationMs,
        long p90DurationMs,
        long maxDurationMs
) {
}
