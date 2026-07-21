package com.rush.rushaicodemother.orchestration.runtime.execution;

import java.time.Duration;
import java.time.Instant;

/** Result of atomically publishing the first usable preview milestone. */
public record GenerationFirstPreviewMilestone(
        boolean firstPublication,
        Instant readyAt,
        Instant deadlineAt,
        Duration elapsed,
        boolean slaBreached
) {
}
