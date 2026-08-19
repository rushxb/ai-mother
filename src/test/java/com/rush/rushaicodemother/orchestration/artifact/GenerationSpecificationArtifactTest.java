package com.rush.rushaicodemother.orchestration.artifact;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationSpecificationArtifactTest {

    @Test
    void shouldRoundTripExecutionFactsAndDeriveModes() {
        GenerationArtifact persisted = GenerationSpecificationArtifact.execution(
                "保留已有登录流程并优化表单交互",
                true,
                true,
                Map.of("modulePlan", List.of("auth", "form"))
        ).toArtifact("Code", "生成规范");

        GenerationSpecificationArtifact restored =
                GenerationSpecificationArtifact.fromArtifact(persisted);

        assertEquals("Code", persisted.role());
        assertEquals("保留已有登录流程并优化表单交互", restored.enhancedPrompt());
        assertTrue(restored.patchFirst());
        assertTrue(restored.requiresBuild());
        assertEquals("build_validation", restored.validationMode());
        assertEquals("patch_first_update", restored.generationMode());
        assertEquals("patch_plan", restored.artifactMode());
        assertEquals(List.of("auth", "form"), persisted.payload().get("modulePlan"));
    }

    @Test
    void postGenerationValidationSpecMustNotPretendToContainExecutionIntent() {
        GenerationArtifact persisted = GenerationSpecificationArtifact
                .postGenerationValidation(true)
                .toArtifact("CREATE", "CREATE 模板生成后验证规范");

        GenerationSpecificationArtifact restored =
                GenerationSpecificationArtifact.fromArtifact(persisted);

        assertTrue(restored.requiresBuild());
        assertFalse(restored.hasExecutionPrompt());
        assertFalse(restored.provesIntentCoverage());
    }

    @Test
    void persistedDerivedModeMustNotContradictBooleanFacts() {
        GenerationArtifact persisted = GenerationSpecificationArtifact.execution(
                "生成可构建的登录页面",
                false,
                true,
                Map.of()
        ).toArtifact("Code", "生成规范");
        Map<String, Object> contradictoryPayload = new LinkedHashMap<>(persisted.payload());
        contradictoryPayload.put("validationMode", "review_only");
        GenerationArtifact corrupted = GenerationArtifact.of(
                persisted.key(),
                persisted.role(),
                persisted.title(),
                contradictoryPayload
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> GenerationSpecificationArtifact.fromArtifact(corrupted)
        );

        assertTrue(exception.getMessage().contains("validationMode"));
    }
}
