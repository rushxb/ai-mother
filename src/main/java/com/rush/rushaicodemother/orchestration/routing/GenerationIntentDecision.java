package com.rush.rushaicodemother.orchestration.routing;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

/**
 * Unified routing decision for a generation request.
 */
public record GenerationIntentDecision(
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
