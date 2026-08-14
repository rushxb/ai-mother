package com.rush.rushaicodemother.orchestration.finalization;

import java.time.Instant;

/** 终态副作用 outbox 在一个时间点的有界运行状态。 */
public record GenerationTerminalEffectBacklog(
        long pending,
        long retrying,
        long leased,
        long deadLetter,
        Instant oldestPendingAt
) {
    public GenerationTerminalEffectBacklog {
        if (pending < 0 || retrying < 0 || leased < 0 || deadLetter < 0) {
            throw new IllegalArgumentException("terminal effect backlog counts must be non-negative");
        }
    }

    public static GenerationTerminalEffectBacklog empty() {
        return new GenerationTerminalEffectBacklog(0, 0, 0, 0, null);
    }
}
