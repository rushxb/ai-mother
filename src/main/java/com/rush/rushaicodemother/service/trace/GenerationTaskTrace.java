package com.rush.rushaicodemother.service.trace;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

import java.time.LocalDateTime;

/** 提供给生成记忆模块的只读任务 trace。 */
public record GenerationTaskTrace(
        String taskId,
        GenerationTaskStatus status,
        String stage,
        String stageMessage,
        String userPrompt,
        String memorySummary,
        String errorMessage,
        LocalDateTime createTime
) {
}
