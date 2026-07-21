package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

import java.time.Instant;

/** Optimistic-lock snapshot used to safely terminalize an expired, non-resumable task. */
public record GenerationTaskRecoveryCandidate(
        String taskId,
        Long appId,
        GenerationTaskStatus status,
        String leaseOwner,
        Instant leaseUntil,
        Instant deadlineAt,
        boolean cancellationRequested,
        String cancellationReason,
        long executionEpoch,
        long version
) {
}
