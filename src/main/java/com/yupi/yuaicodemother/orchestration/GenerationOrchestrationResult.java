package com.yupi.yuaicodemother.orchestration;

import com.yupi.yuaicodemother.core.handler.GenerationStreamEvent;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;

import java.util.List;

/**
 * 应用生成编排结果。
 */
public record GenerationOrchestrationResult(
        CodeGenTypeEnum originalType,
        CodeGenTypeEnum targetType,
        boolean upgradeRequired,
        String generatingStage,
        String enhancedMessage,
        List<GenerationStreamEvent> events
) {
}
