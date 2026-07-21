package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationPerformanceSelectorTest {

    private final GenerationPerformanceSelector selector = new GenerationPerformanceSelector();

    @Test
    void complexBackendCreationMustUseDeepReasoning() {
        GenerationPerformanceProfile profile = selector.select(
                true, true, CodeGenTypeEnum.BACKEND_PROJECT);

        assertEquals(GenerationThinkingMode.DEEP, profile.thinkingMode());
        assertTrue(profile.thinkingEnabled());
    }

    @Test
    void simpleFirstGenerationMustRemainFast() {
        GenerationPerformanceProfile profile = selector.select(
                true, false, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals(GenerationThinkingMode.FAST, profile.thinkingMode());
    }
}
