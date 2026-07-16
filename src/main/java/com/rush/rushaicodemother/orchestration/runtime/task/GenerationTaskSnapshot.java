package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimate;

import java.time.Instant;
import java.util.Map;

/** Immutable task status view exposed through the application and HTTP query seams. */
public record GenerationTaskSnapshot(
        String taskId,
        Long appId,
        Long userId,
        String route,
        String status,
        String stage,
        String stageMessage,
        Instant submittedAt,
        Instant deadlineAt,
        boolean cancellationRequested,
        String cancellationReason,
        Map<GenerationBudgetKind, Integer> usages,
        Map<GenerationBudgetKind, Integer> limits,
        GenerationTaskProgressEstimate progress
) {
}
