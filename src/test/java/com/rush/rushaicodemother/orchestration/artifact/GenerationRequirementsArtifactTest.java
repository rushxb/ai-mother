package com.rush.rushaicodemother.orchestration.artifact;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRequirementsArtifactTest {

    @Test
    void shouldDeriveExecutionModesAndRoundTripThePlanningFacts() {
        GenerationRequirementsArtifact requirements = GenerationRequirementsArtifact.create(
                true,
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                true,
                true,
                true,
                "semantic_index",
                "增加商品管理",
                List.of(Map.of("path", "frontend/src/App.vue")),
                List.of("保留已有能力", "同步前后端字段契约"),
                List.of(Map.of("id", "crud-form-flow", "title", "CRUD")),
                List.of(Map.of("id", "frontend-design", "title", "Design"))
        );

        GenerationArtifact persisted = requirements.toArtifact();
        GenerationRequirementsArtifact restored = GenerationRequirementsArtifact.fromArtifact(
                persisted, CodeGenTypeEnum.FULL_STACK_PROJECT);

        assertEquals(GenerationRequirementsArtifact.KEY, persisted.key());
        assertTrue(restored.complex());
        assertEquals(Optional.of(true), restored.plannedComplexity());
        assertTrue(restored.patchFirst());
        assertTrue(restored.requiresBuild());
        assertEquals("patch_first_update", restored.generationMode());
        assertEquals("build_validation", restored.validationMode());
        assertEquals("heavy", restored.orchestrationMode());
        assertEquals(List.of("保留已有能力", "同步前后端字段契约"), restored.goals());
        assertEquals(List.of("crud-form-flow"), persisted.payload().get("recipeIds"));
        assertEquals(List.of("frontend-design"), persisted.payload().get("skillIds"));
        assertTrue(restored.provesIntentCoverage(CodeGenTypeEnum.FULL_STACK_PROJECT));
        assertFalse(restored.provesIntentCoverage(CodeGenTypeEnum.BACKEND_PROJECT));
    }

    @Test
    void legacyCheckpointWithOnlyRoutingFactsMustReceiveSafeDefaults() {
        GenerationArtifact legacy = GenerationArtifact.of(
                GenerationRequirementsArtifact.KEY,
                "Planner",
                "requirements",
                Map.of("targetType", CodeGenTypeEnum.VUE_PROJECT.getValue())
        );

        GenerationRequirementsArtifact restored = GenerationRequirementsArtifact.fromArtifact(
                legacy, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals(false, restored.complex());
        assertEquals(Optional.empty(), restored.plannedComplexity());
        assertEquals(false, restored.upgradeRequired());
        assertEquals(false, restored.patchFirst());
        assertEquals(false, restored.requiresBuild());
        assertEquals("review_only", restored.validationMode());
        assertEquals("full_generation", restored.generationMode());
        assertEquals("legacy_checkpoint", restored.contextRecallSource());
        assertEquals(List.of(), restored.goals());
        assertEquals(List.of(), restored.recipes());
        assertEquals(List.of(), restored.skills());
        assertFalse(restored.provesIntentCoverage(CodeGenTypeEnum.VUE_PROJECT));
    }

    @Test
    void persistedModeMustNotContradictItsBooleanSourceOfTruth() {
        GenerationArtifact canonical = GenerationRequirementsArtifact.create(
                false,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                false,
                false,
                "new_project",
                "创建官网",
                List.of(),
                List.of("生成首页"),
                List.of(),
                List.of()
        ).toArtifact();
        Map<String, Object> conflictingPayload = new java.util.LinkedHashMap<>(canonical.payload());
        conflictingPayload.put("validationMode", "build_validation");
        GenerationArtifact conflicting = GenerationArtifact.of(
                GenerationRequirementsArtifact.KEY,
                "Planner",
                "需求与目标",
                conflictingPayload
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> GenerationRequirementsArtifact.fromArtifact(
                        conflicting, CodeGenTypeEnum.VUE_PROJECT)
        );

        assertTrue(exception.getMessage().contains("validationMode"));
    }

    @Test
    void legacyRecoveryDefaultsMustNotBecomeIntentCoverageEvidence() {
        GenerationRequirementsArtifact legacyShaped = GenerationRequirementsArtifact.create(
                false,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                false,
                false,
                "legacy_checkpoint",
                "创建官网",
                List.of(),
                List.of("生成首页"),
                List.of(),
                List.of()
        );

        assertFalse(legacyShaped.provesIntentCoverage(CodeGenTypeEnum.VUE_PROJECT));
    }
}
