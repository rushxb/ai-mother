package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

import java.time.Instant;

/** 乐观锁快照用于安全地终止过期的、不可恢复的任务。 */
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
