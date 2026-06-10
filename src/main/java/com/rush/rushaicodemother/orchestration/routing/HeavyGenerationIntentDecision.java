package com.rush.rushaicodemother.orchestration.routing;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

/**
 * Heavy generation input assembled after the top-level mode router selected HEAVY_EXPERT.
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
