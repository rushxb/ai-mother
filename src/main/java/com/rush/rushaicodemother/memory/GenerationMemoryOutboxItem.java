package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

/**
 * 生成记忆事务发件箱条目的不可变数据载体。
 */
public record GenerationMemoryOutboxItem(
        String taskId,
        Long tenantId,
        Long appId,
        Long userId,
        GenerationTaskStatus status,
        String userPrompt,
        String memorySummary,
        String orchestrationMode,
        String targetCodeGenType,
        int attempts
) {
    GenerationOutcomeMemoryRequest toMemoryRequest() {
        return new GenerationOutcomeMemoryRequest(
                taskId,
                tenantId,
                appId,
                userId,
                status,
                userPrompt,
                memorySummary,
                orchestrationMode,
                targetCodeGenType
        );
    }
}
