package com.rush.rushaicodemother.orchestration.routing;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

/**
 * 顶级模式路由器选择HEAVY_EXPERT后重载生成输入组装。
 */
public record HeavyGenerationIntentDecision(
        String route,
        String reason,
        double confidence,
        CodeGenTypeEnum currentType,
        CodeGenTypeEnum targetType,
        String generationMessage,
        String generatingStage,
        boolean hasGeneratedCode,
        boolean requiresBuild
) {

    public boolean lightweightEdit() {
        return GenerationRoute.LIGHTWEIGHT_EDIT.equals(route);
    }

    public boolean heavyGeneration() {
        return GenerationRoute.HEAVY_GENERATION.equals(route);
    }
}
