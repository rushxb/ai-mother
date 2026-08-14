package com.rush.rushaicodemother.model.vo;

import java.time.Instant;

/** 终态副作用 dead-letter 重放结果。 */
public record GenerationTerminalEffectReplayVO(
        String taskId,
        long executionEpoch,
        Instant requestedAt
) {
}
