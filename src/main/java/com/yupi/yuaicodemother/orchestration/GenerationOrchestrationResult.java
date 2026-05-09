package com.yupi.yuaicodemother.orchestration;

import com.yupi.yuaicodemother.core.handler.GenerationStreamEvent;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.artifact.QualityGateResult;

import java.util.List;
import java.util.Map;

/**
 * 应用生成编排结果。
 */
public record GenerationOrchestrationResult(
        CodeGenTypeEnum originalType,
        CodeGenTypeEnum targetType,
        boolean upgradeRequired,
        String generatingStage,
        String enhancedMessage,
        List<GenerationStreamEvent> events,
        Map<String, GenerationArtifact> artifacts,
        QualityGateResult qualityGateResult,
        Map<String, Long> timings,
        String taskId
) {
}
