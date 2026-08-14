package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffectAdminItem;

import java.time.Instant;

/** 终态副作用 outbox 管理项视图。 */
public record GenerationTerminalEffectItemVO(
        String taskId,
        Long appId,
        String route,
        long executionEpoch,
        String state,
        int attempts,
        String lastError,
        Instant nextAttemptAt,
        Instant leaseUntil,
        Instant finalizedAt
) {
    public static GenerationTerminalEffectItemVO from(GenerationTerminalEffectAdminItem item) {
        return new GenerationTerminalEffectItemVO(
                item.taskId(), item.appId(), item.route(), item.executionEpoch(), item.state(),
                item.attempts(), item.lastError(), item.nextAttemptAt(), item.leaseUntil(),
                item.finalizedAt());
    }
}
