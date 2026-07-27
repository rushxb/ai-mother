package com.rush.rushaicodemother.orchestration.runtime.execution;

import java.time.Instant;
import java.util.Map;

/**
 * 不可变的运行时快照适用于遥测和以后的持久性。
 */
public record GenerationExecutionSnapshot(
        String taskId,
        Long appId,
        Long userId,
        Instant startedAt,
        Instant deadlineAt,
        String slaProfile,
        Instant firstPreviewDeadlineAt,
        Instant firstPreviewReadyAt,
        boolean cancelled,
        String cancellationReason,
        String terminalStatus,
        int successfulWorkspaceMutations,
        Map<GenerationBudgetKind, Integer> usages,
        Map<GenerationBudgetKind, Integer> limits
) {

    public GenerationExecutionSnapshot {
        slaProfile = slaProfile == null || slaProfile.isBlank() ? "legacy-default" : slaProfile.trim();
        firstPreviewDeadlineAt = firstPreviewDeadlineAt == null ? deadlineAt : firstPreviewDeadlineAt;
        if (successfulWorkspaceMutations < 0) {
            throw new IllegalArgumentException("成功工作区变更数不能小于 0");
        }
    }

    /** 兼容尚未携带成功工作区变更数的旧检查点。 */
    public GenerationExecutionSnapshot(
            String taskId,
            Long appId,
            Long userId,
            Instant startedAt,
            Instant deadlineAt,
            String slaProfile,
            Instant firstPreviewDeadlineAt,
            Instant firstPreviewReadyAt,
            boolean cancelled,
            String cancellationReason,
            String terminalStatus,
            Map<GenerationBudgetKind, Integer> usages,
            Map<GenerationBudgetKind, Integer> limits
    ) {
        this(taskId, appId, userId, startedAt, deadlineAt, slaProfile,
                firstPreviewDeadlineAt, firstPreviewReadyAt, cancelled,
                cancellationReason, terminalStatus, 0, usages, limits);
    }
}
