package com.rush.rushaicodemother.orchestration.tool;

import java.time.Instant;

/**
 * 工具审批不可变记录。
 */
public record ToolApprovalRecord(
        String approvalId,
        String taskId,
        Long appId,
        Long userId,
        DestructiveToolAction action,
        String requestJson,
        ToolApprovalStatus status,
        Instant requestedAt,
        Instant expiresAt,
        Long decidedBy,
        Instant decidedAt,
        Instant consumedAt,
        long version,
        ToolInvocationCheckpoint invocationCheckpoint,
        Instant executionStartedAt,
        ToolExecutionOutcome executionOutcome,
        int executionAttempt
) {

    public ToolApprovalRecord(
            String approvalId,
            String taskId,
            Long appId,
            Long userId,
            DestructiveToolAction action,
            String requestJson,
            ToolApprovalStatus status,
            Instant requestedAt,
            Instant expiresAt,
            Long decidedBy,
            Instant decidedAt,
            Instant consumedAt,
            long version,
            ToolInvocationCheckpoint invocationCheckpoint
    ) {
        this(approvalId, taskId, appId, userId, action, requestJson, status,
                requestedAt, expiresAt, decidedBy, decidedAt, consumedAt, version,
                invocationCheckpoint, null, null, 0);
    }
}
