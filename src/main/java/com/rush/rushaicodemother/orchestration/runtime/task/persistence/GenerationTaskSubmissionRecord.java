package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import java.time.Instant;
import java.util.Objects;

/** Stable metadata required to durably accept a generation task. */
public record GenerationTaskSubmissionRecord(
        String taskId,
        Long appId,
        Long userId,
        String route,
        Instant submittedAt,
        Instant deadlineAt,
        String leaseOwner,
        Instant leaseUntil
) {
    public GenerationTaskSubmissionRecord {
        requireText(taskId, "taskId");
        requirePositive(appId, "appId");
        requirePositive(userId, "userId");
        requireText(route, "route");
        Objects.requireNonNull(submittedAt, "submittedAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        requireText(leaseOwner, "leaseOwner");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (!deadlineAt.isAfter(submittedAt)) {
            throw new IllegalArgumentException("deadlineAt must be after submittedAt");
        }
        if (!leaseUntil.isAfter(submittedAt)) {
            throw new IllegalArgumentException("leaseUntil must be after submittedAt");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
