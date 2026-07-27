package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

import java.time.Instant;

/** 持久任务状态，无需进程本地会话、流或工作区。 */
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
