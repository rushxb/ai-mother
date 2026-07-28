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
        long agentAttemptEpoch,
        int agentToolRoundLimit,
        int agentModelTurnsStarted,
        Map<GenerationBudgetKind, Integer> usages,
        Map<GenerationBudgetKind, Integer> limits
) {

    /** 创建生成执行快照实例并完成必要的依赖和初始状态设置。 */
    public GenerationExecutionSnapshot {
        slaProfile = slaProfile == null || slaProfile.isBlank() ? "legacy-default" : slaProfile.trim();
        firstPreviewDeadlineAt = firstPreviewDeadlineAt == null ? deadlineAt : firstPreviewDeadlineAt;
        if (successfulWorkspaceMutations < 0) {
            throw new IllegalArgumentException("成功工作区变更数不能小于 0");
        }
        if (agentAttemptEpoch < 0 || agentToolRoundLimit < 0 || agentModelTurnsStarted < 0) {
            throw new IllegalArgumentException("Agent 回合快照不能包含负数");
        }
        if (agentAttemptEpoch == 0L
                && (agentToolRoundLimit != 0 || agentModelTurnsStarted != 0)) {
            throw new IllegalArgumentException("Agent 回合快照缺少尝试纪元");
        }
        if (agentAttemptEpoch > 0L
                && (agentToolRoundLimit <= 0
                || agentModelTurnsStarted > Math.addExact(agentToolRoundLimit, 1))) {
            throw new IllegalArgumentException("Agent 回合快照超出当前尝试预算");
        }
    }

    /** 兼容尚未携带 Agent 回合账本的旧检查点。 */
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
            int successfulWorkspaceMutations,
            Map<GenerationBudgetKind, Integer> usages,
            Map<GenerationBudgetKind, Integer> limits
    ) {
        this(taskId, appId, userId, startedAt, deadlineAt, slaProfile,
                firstPreviewDeadlineAt, firstPreviewReadyAt, cancelled,
                cancellationReason, terminalStatus, successfulWorkspaceMutations,
                0L, 0, 0, usages, limits);
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
                cancellationReason, terminalStatus, 0,
                0L, 0, 0, usages, limits);
    }
}
