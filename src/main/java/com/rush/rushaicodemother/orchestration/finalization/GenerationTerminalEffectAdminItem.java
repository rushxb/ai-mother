package com.rush.rushaicodemother.orchestration.finalization;

import java.time.Instant;

/** 管理端可见的终态副作用摘要，不暴露冻结命令或用户输入。 */
public record GenerationTerminalEffectAdminItem(
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
}
