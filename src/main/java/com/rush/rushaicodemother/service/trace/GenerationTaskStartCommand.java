package com.rush.rushaicodemother.service.trace;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

/** 创建生成任务 trace 的完整命令。 */
public record GenerationTaskStartCommand(
        String taskId,
        Long appId,
        Long userId,
        CodeGenTypeEnum originalType,
        CodeGenTypeEnum targetType,
        String userPrompt,
        String enhancedPrompt,
        boolean requiresBuildValidation,
        String qualityGate,
        String orchestrationMode
) {
}
