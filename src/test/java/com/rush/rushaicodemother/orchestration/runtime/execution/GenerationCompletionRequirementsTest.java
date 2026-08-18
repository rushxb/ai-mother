package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationCompletionRequirementsTest {

    @Test
    void legacyTasksMustPreserveRuntimeValidationForEveryBuildableProject() {
        assertEquals(GenerationCompletionRequirements.none(),
                GenerationCompletionRequirements.legacy(CodeGenTypeEnum.HTML));
        assertEquals(GenerationCompletionRequirements.none(),
                GenerationCompletionRequirements.legacy(CodeGenTypeEnum.MULTI_FILE));
        assertEquals(GenerationCompletionRequirements.buildAndRuntime(),
                GenerationCompletionRequirements.legacy(CodeGenTypeEnum.VUE_PROJECT));
        assertEquals(GenerationCompletionRequirements.buildAndRuntime(),
                GenerationCompletionRequirements.legacy(CodeGenTypeEnum.BACKEND_PROJECT));
        assertEquals(GenerationCompletionRequirements.buildAndRuntime(),
                GenerationCompletionRequirements.legacy(CodeGenTypeEnum.FULL_STACK_PROJECT));
    }

    @Test
    void runtimeValidationMustNeverExistWithoutBuildValidation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GenerationCompletionRequirements(false, true)
        );
    }

    @Test
    void oldExecutionLimitCheckpointWithoutRequirementsMustRestoreConservatively() throws Exception {
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 2);
        }
        GenerationExecutionLimits limits = new GenerationExecutionLimits(
                Duration.ofMinutes(10),
                Duration.ofMinutes(2),
                Duration.ofMillis(500),
                GenerationCompletionRequirements.none(),
                budgets
        );
        JsonNode legacyJson = mapper.valueToTree(limits);
        ((ObjectNode) legacyJson).remove("completionRequirements");

        GenerationExecutionLimits restored = mapper.treeToValue(
                legacyJson,
                GenerationExecutionLimits.class
        );

        assertEquals(
                GenerationCompletionRequirements.buildAndRuntime(),
                restored.completionRequirements()
        );
    }
}
