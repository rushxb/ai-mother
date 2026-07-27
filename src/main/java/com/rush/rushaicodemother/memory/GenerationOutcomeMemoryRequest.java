package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

/** 生成任务结果写入语义记忆所需的稳定输入。 */
public record GenerationOutcomeMemoryRequest(
        String taskId,
        Long tenantId,
        Long appId,
        Long userId,
        GenerationTaskStatus status,
        String userPrompt,
        String memorySummary,
        String orchestrationMode,
        String targetCodeGenType
) {
}
