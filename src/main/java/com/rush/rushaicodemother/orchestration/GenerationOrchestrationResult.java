package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;

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
