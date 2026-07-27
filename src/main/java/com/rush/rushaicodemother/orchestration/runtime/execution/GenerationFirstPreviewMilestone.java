package com.rush.rushaicodemother.orchestration.runtime.execution;

import java.time.Duration;
import java.time.Instant;

/** 以原子方式发布第一个可用预览里程碑的结果。 */
public record GenerationFirstPreviewMilestone(
        boolean firstPublication,
        Instant readyAt,
        Instant deadlineAt,
        Duration elapsed,
        boolean slaBreached
) {
}
