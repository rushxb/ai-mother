package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffectBacklog;

import java.time.Duration;
import java.time.Instant;

/** 终态副作用 outbox 管理端积压视图。 */
public record GenerationTerminalEffectBacklogVO(
        long pending,
        long retrying,
        long leased,
        long deadLetter,
        Instant oldestPendingAt,
        long oldestAgeSeconds,
        Instant observedAt
) {
    public static GenerationTerminalEffectBacklogVO from(
            GenerationTerminalEffectBacklog backlog,
            Instant observedAt) {
        Instant oldest = backlog.oldestPendingAt();
        long age = oldest == null ? 0L
                : Math.max(0L, Duration.between(oldest, observedAt).toSeconds());
        return new GenerationTerminalEffectBacklogVO(
                backlog.pending(), backlog.retrying(), backlog.leased(), backlog.deadLetter(),
                oldest, age, observedAt);
    }
}
