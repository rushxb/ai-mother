package com.rush.rushaicodemother.memory;

import java.time.Instant;

/** 一个持久语义记忆发件箱的时间点操作状态。 */
public record SemanticMemoryOutboxBacklog(
        long pending,
        long retrying,
        long leased,
        long deadLetter,
        Instant oldestPendingAt
) {
    /** 创建语义记忆事务发件箱积压量实例并完成必要的依赖和初始状态设置。 */
    public SemanticMemoryOutboxBacklog {
        if (pending < 0 || retrying < 0 || leased < 0 || deadLetter < 0) {
            throw new IllegalArgumentException("semantic-memory outbox counts must be non-negative");
        }
    }

    public static SemanticMemoryOutboxBacklog empty() {
        return new SemanticMemoryOutboxBacklog(0, 0, 0, 0, null);
    }
}
