package com.rush.rushaicodemother.orchestration.runtime.task.progress;

import java.util.List;

/** Persistence port for bounded historical task and operation duration samples. */
public interface GenerationDurationSampleRepository {

    GenerationDurationSamples loadRecentSuccessfulSamples(String route,
                                                           int taskSampleLimit,
                                                           int spanSampleLimit);

    record GenerationDurationSamples(
            List<Long> taskDurationsMs,
            List<GenerationStageDurationSample> stageDurations
    ) {
        public GenerationDurationSamples {
            taskDurationsMs = taskDurationsMs == null ? List.of() : List.copyOf(taskDurationsMs);
            stageDurations = stageDurations == null ? List.of() : List.copyOf(stageDurations);
        }
    }

    record GenerationStageDurationSample(
            String stage,
            String category,
            long durationMs
    ) {
    }
}
