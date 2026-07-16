package com.rush.rushaicodemother.orchestration.runtime.task.progress;

import java.time.Instant;
import java.util.List;

/** Cached route-level duration profile used by diagnostics and user-facing ETA estimation. */
public record GenerationDurationProfile(
        String route,
        int taskSampleSize,
        long p50TotalDurationMs,
        long p90TotalDurationMs,
        long maxTotalDurationMs,
        List<GenerationStageDurationProfile> stages,
        Instant computedAt
) {
    public GenerationDurationProfile {
        stages = stages == null ? List.of() : List.copyOf(stages);
    }
}
