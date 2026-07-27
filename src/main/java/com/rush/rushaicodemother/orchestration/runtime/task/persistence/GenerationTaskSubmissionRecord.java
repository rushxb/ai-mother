package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import java.time.Instant;
import java.util.Objects;

/** 持久接受生成任务需要稳定的元数据。 */
public record GenerationTaskSubmissionRecord(
        String taskId,
        Long appId,
        Long userId,
        Long tenantId,
        String route,
        Instant submittedAt,
        Instant deadlineAt,
        String idempotencyKeyHash,
        String requestFingerprint,
        GenerationTaskCommand command
) {
    public GenerationTaskSubmissionRecord {
        requireText(taskId, "taskId");
        requirePositive(appId, "appId");
        requirePositive(userId, "userId");
        requirePositive(tenantId, "tenantId");
        requireText(route, "route");
        Objects.requireNonNull(submittedAt, "submittedAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        Objects.requireNonNull(command, "command");
        if ((idempotencyKeyHash == null) != (requestFingerprint == null)) {
            throw new IllegalArgumentException("submission idempotency hashes must be paired");
        }
        if (idempotencyKeyHash != null
                && (!idempotencyKeyHash.matches("[0-9a-f]{64}")
                || !requestFingerprint.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("submission idempotency hashes must be lowercase SHA-256 values");
        }
        if (!deadlineAt.isAfter(submittedAt)) {
            throw new IllegalArgumentException("deadlineAt must be after submittedAt");
        }
        if (!taskId.equals(command.taskId())
                || !appId.equals(command.appId())
                || !userId.equals(command.userId())
                || (command.tenantId() != null && !tenantId.equals(command.tenantId()))
                || !route.equals(command.route())
                || !submittedAt.equals(command.submittedAt())
                || !deadlineAt.equals(command.deadlineAt())) {
            throw new IllegalArgumentException("generation command does not match submission identity");
        }
    }

    public GenerationTaskSubmissionRecord(String taskId,
                                          Long appId,
                                          Long userId,
                                          Long tenantId,
                                          String route,
                                          Instant submittedAt,
                                          Instant deadlineAt,
                                          GenerationTaskCommand command) {
        this(taskId, appId, userId, tenantId, route, submittedAt, deadlineAt,
                null, null, command);
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
