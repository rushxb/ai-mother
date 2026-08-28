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
        long executionEpoch,
        int attempt,
        long version,
        Instant completedAt,
        String errorMessage
) {

    /**
     * 兼容只消费任务状态的旧调用方；安全敏感流程必须使用包含执行纪元的完整构造器。
     */
    public DurableGenerationTaskRecord(
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
        this(taskId, appId, userId, tenantId, route, status, stage, stageMessage,
                submittedAt, deadlineAt, cancellationRequested, cancellationReason,
                leaseOwner, leaseUntil, heartbeatAt, 0L, attempt, version,
                completedAt, errorMessage);
    }

    public boolean terminal() {
        return status != null && status.isTerminal();
    }
}
