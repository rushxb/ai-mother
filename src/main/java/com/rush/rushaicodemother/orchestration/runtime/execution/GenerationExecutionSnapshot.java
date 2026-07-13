package com.rush.rushaicodemother.orchestration.runtime.execution;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable runtime snapshot suitable for telemetry and later durable persistence.
 */
public record GenerationExecutionSnapshot(
        String taskId,
        Long appId,
        Long userId,
        Instant startedAt,
        Instant deadlineAt,
        boolean cancelled,
        String cancellationReason,
        String terminalStatus,
        Map<GenerationBudgetKind, Integer> usages,
        Map<GenerationBudgetKind, Integer> limits
) {
}
