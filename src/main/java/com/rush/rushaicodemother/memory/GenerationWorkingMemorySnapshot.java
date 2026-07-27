package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;

import java.time.Instant;
import java.util.List;

/**
 * 生成工作记忆快照的不可变数据载体。
 */
public record GenerationWorkingMemorySnapshot(
        String taskId,
        Long appId,
        Long userId,
        String route,
        String currentStage,
        String currentSummary,
        String contextDigest,
        boolean completed,
        Instant updatedAt,
        List<GenerationStreamEvent> recentEvents
) {
}
