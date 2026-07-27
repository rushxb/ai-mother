package com.rush.rushaicodemother.orchestration.runtime.task.progress;

import java.time.Instant;
import java.util.List;

/** 诊断和面向用户的 ETA 估计使用的缓存路线级持续时间配置文件。 */
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
