package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationStageDurationProfile;

/** Administrator view of one historical generation operation duration profile. */
public record GenerationStageDurationProfileVO(
        String stage,
        String category,
        int sampleSize,
        long p50DurationMs,
        long p90DurationMs,
        long maxDurationMs
) {
    public static GenerationStageDurationProfileVO from(GenerationStageDurationProfile profile) {
        return new GenerationStageDurationProfileVO(
                profile.stage(), profile.category(), profile.sampleSize(),
                profile.p50DurationMs(), profile.p90DurationMs(), profile.maxDurationMs());
    }
}
