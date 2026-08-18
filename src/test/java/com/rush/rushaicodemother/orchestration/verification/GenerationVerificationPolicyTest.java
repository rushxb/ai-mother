package com.rush.rushaicodemother.orchestration.verification;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.edit.EditValidationPlan;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationCompletionRequirements;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationVerificationPolicyTest {

    @Test
    void buildPlanMustRequireBuildWithoutRunningExpertRuntimeValidation() {
        GenerationVerificationPolicy policy = GenerationVerificationPolicy.planned(
                GenerationExecutionPlan.ValidationGraph.forLevel(ExpectedValidationLevel.BUILD));

        assertTrue(policy.requiresFastCheck());
        assertTrue(policy.requiresBuildValidation(preparation(CodeGenTypeEnum.VUE_PROJECT)));
        assertFalse(policy.requiresExpertCheck());
        assertFalse(policy.requiresRuntimeValidation());
        assertEquals(
                GenerationCompletionRequirements.buildOnly(),
                policy.completionRequirements(CodeGenTypeEnum.VUE_PROJECT)
        );
    }

    @Test
    void expertPlanMustRequireRuntimeValidation() {
        GenerationVerificationPolicy policy = GenerationVerificationPolicy.planned(
                GenerationExecutionPlan.ValidationGraph.forLevel(ExpectedValidationLevel.EXPERT));

        assertTrue(policy.requiresExpertCheck());
        assertTrue(policy.requiresRuntimeValidation());
        assertEquals(
                GenerationCompletionRequirements.buildAndRuntime(),
                policy.completionRequirements(CodeGenTypeEnum.BACKEND_PROJECT)
        );
    }

    @Test
    void frozenPlanIsMinimumFloorAndPreparationMayUpgradeBuildRisk() {
        GenerationVerificationPolicy policy = GenerationVerificationPolicy.planned(
                GenerationExecutionPlan.ValidationGraph.forLevel(ExpectedValidationLevel.FAST));

        assertFalse(policy.requiresBuildValidation(preparation(CodeGenTypeEnum.HTML)));
        assertTrue(policy.requiresBuildValidation(preparation(CodeGenTypeEnum.BACKEND_PROJECT)));
        assertEquals(
                GenerationCompletionRequirements.none(),
                policy.completionRequirements(CodeGenTypeEnum.HTML)
        );
        assertEquals(
                GenerationCompletionRequirements.buildOnly(),
                policy.completionRequirements(CodeGenTypeEnum.BACKEND_PROJECT)
        );
    }

    @Test
    void plannedPolicyMustRaiseEditValidationToFrozenMinimum() {
        EditValidationPlan dynamicPlan = new EditValidationPlan(
                EditValidationPlan.ValidationLevel.NONE,
                "动态规则判定无需验证",
                List.of("src/App.vue"),
                false
        );
        GenerationVerificationPolicy buildPolicy = GenerationVerificationPolicy.planned(
                GenerationExecutionPlan.ValidationGraph.forLevel(ExpectedValidationLevel.BUILD));
        GenerationVerificationPolicy expertPolicy = GenerationVerificationPolicy.planned(
                GenerationExecutionPlan.ValidationGraph.forLevel(ExpectedValidationLevel.EXPERT));

        EditValidationPlan buildPlan = buildPolicy.enforceEditMinimum(dynamicPlan);
        EditValidationPlan expertPlan = expertPolicy.enforceEditMinimum(dynamicPlan);

        assertTrue(buildPlan.requiresBuild());
        assertTrue(expertPlan.requiresHeavyReview());
        assertTrue(buildPlan.reason().contains("执行计划最低验证门槛"));
    }
    @Test
    void legacyPolicyMustPreserveExistingHeavyRuntimeBehavior() {
        GenerationVerificationPolicy policy = GenerationVerificationPolicy.legacy();

        assertTrue(policy.requiresBuildValidation(preparation(CodeGenTypeEnum.VUE_PROJECT)));
        assertTrue(policy.requiresRuntimeValidation());
    }

    private GenerationPreparation preparation(CodeGenTypeEnum codeGenType) {
        return new GenerationPreparation(
                codeGenType,
                codeGenType,
                false,
                "test",
                "test",
                List.of(),
                new HashMap<>(),
                null,
                Map.of(),
                "verification-policy-test"
        );
    }
}
