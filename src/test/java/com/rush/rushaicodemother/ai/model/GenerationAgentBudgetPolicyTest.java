package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationAgentBudgetPolicyTest {

    private final GenerationAgentBudgetPolicy policy = new GenerationAgentBudgetPolicy();

    @Test
    void plannedProfileMustApplyProjectFloorsAndReserveFinalResponse() {
        GenerationPerformanceProfile profile = GenerationPerformanceProfile.qualityFirst();

        GenerationAgentBudgetPolicy.GenerationAgentBudget backend = policy.resolve(
                CodeGenTypeEnum.BACKEND_PROJECT,
                profile
        );
        GenerationAgentBudgetPolicy.GenerationAgentBudget fullStack = policy.resolve(
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                profile
        );

        assertEquals(15, backend.toolRoundLimit());
        assertEquals(16, backend.maximumModelResponses());
        assertEquals(15, backend.effectiveProfile().maxToolInvocations());
        assertEquals(20, fullStack.toolRoundLimit());
        assertEquals(21, fullStack.maximumModelResponses());
        assertEquals(
                GenerationAgentBudgetPolicy.BudgetSource.PLANNED_PROFILE,
                fullStack.source()
        );
    }

    @Test
    void legacyTaskWithoutProfileMustPreserveHistoricalLimits() {
        assertLegacyLimit(CodeGenTypeEnum.HTML, 10);
        assertLegacyLimit(CodeGenTypeEnum.MULTI_FILE, 10);
        assertLegacyLimit(CodeGenTypeEnum.VUE_PROJECT, 10);
        assertLegacyLimit(CodeGenTypeEnum.BACKEND_PROJECT, 20);
        assertLegacyLimit(CodeGenTypeEnum.FULL_STACK_PROJECT, 32);
    }

    @Test
    void invalidPlannedLimitMustFailBeforeStartingAgentAttempt() {
        GenerationPerformanceProfile invalid = new GenerationPerformanceProfile(
                GenerationPerformanceProfile.ModelTier.BALANCED,
                false,
                0,
                "无效测试预算"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> policy.resolve(CodeGenTypeEnum.VUE_PROJECT, invalid)
        );
    }

    private void assertLegacyLimit(CodeGenTypeEnum codeGenType, int expectedToolRounds) {
        GenerationAgentBudgetPolicy.GenerationAgentBudget budget = policy.resolve(
                codeGenType,
                null
        );

        assertEquals(expectedToolRounds, budget.toolRoundLimit());
        assertEquals(expectedToolRounds + 1, budget.maximumModelResponses());
        assertEquals(
                GenerationAgentBudgetPolicy.BudgetSource.LEGACY_COMPATIBILITY,
                budget.source()
        );
    }
}
