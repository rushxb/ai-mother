package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

import java.time.Instant;

/** Durable task state without process-local sessions, streams or workspaces. */
public record DurableGenerationTaskRecord(
        String taskId,
        Long appId,
        Long userId,
        Long tenantId,
        String route,
        GenerationTaskStatus status,
        String stage,
        String stageMessage,
        Instant submittedAt,
        Instant deadlineAt,
        boolean cancellationRequested,
        String cancellationReason,
        String leaseOwner,
        Instant leaseUntil,
        Instant heartbeatAt,
        int attempt,
        long version,
        Instant completedAt,
        String errorMessage
) {
    public boolean terminal() {
        return status != null && status.isTerminal();
    }
}
