package com.rush.rushaicodemother.orchestration.plan;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceSelector;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
import com.rush.rushaicodemother.orchestration.intent.IntentDestructiveRisk;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity;
import com.rush.rushaicodemother.orchestration.intent.IntentValidationRisk;
import com.rush.rushaicodemother.orchestration.context.AiContextPackBudgetProperties;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaEnvelope;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class GenerationExecutionPlannerTest {

    @Test
    void plannerMustResolveAllExecutionConstraintsExactlyOnce() {
        GenerationSlaPolicy slaPolicy = mock(GenerationSlaPolicy.class);
        GenerationPerformanceSelector performanceSelector = mock(GenerationPerformanceSelector.class);
        AiContextPackBudgetProperties contextProperties = contextProperties();
        GenerationPipelineRequest request = request();
        GenerationSlaEnvelope sla = slaEnvelope();
        GenerationPerformanceProfile modelProfile = GenerationPerformanceProfile.qualityFirst();
        when(slaPolicy.resolve(request.modeDecision(), request.codeGenType())).thenReturn(sla);
        when(performanceSelector.select(false, true, request.codeGenType())).thenReturn(modelProfile);
        GenerationExecutionPlanner planner = new GenerationExecutionPlanner(
                slaPolicy, performanceSelector, contextProperties);

        GenerationExecutionPlan plan = planner.plan(request);

        assertSame(request.modeDecision(), plan.route());
        assertSame(modelProfile, plan.modelProfile());
        assertEquals(contextProperties.getGenerationMaxTokens(), plan.contextBudget().generationMaxTokens());
        assertEquals(contextProperties.getRepairMaxTokens(), plan.contextBudget().repairMaxTokens());
        assertEquals(modelProfile.maxToolInvocations(), plan.toolPolicy().maxInvocations());
        assertEquals(sla.toLimits().limit(GenerationBudgetKind.TOOL_WRITE),
                plan.toolPolicy().maxWriteOperations());
        assertEquals(ExpectedValidationLevel.EXPERT, plan.validationGraph().level());
        assertEquals(sla.toLimits().limit(GenerationBudgetKind.REPAIR_ROUND),
                plan.repairBudget().maxRounds());
        assertTrue(plan.commitPolicy().requireValidationSuccess());
        assertEquals(sla.firstPreviewTimeout(), plan.previewPolicy().firstPreviewTimeout());
        assertSame(sla, plan.sla());
        verify(slaPolicy).resolve(request.modeDecision(), request.codeGenType());
        verify(performanceSelector).select(false, true, request.codeGenType());
        verifyNoMoreInteractions(slaPolicy, performanceSelector);
    }

    @Test
    void readOnlyPlanMustStructurallyDisableEveryMutationBudget() {
        GenerationSlaPolicy slaPolicy = mock(GenerationSlaPolicy.class);
        GenerationPerformanceSelector performanceSelector = mock(GenerationPerformanceSelector.class);
        GenerationPipelineRequest request = readOnlyRequest();
        GenerationSlaEnvelope sla = readOnlySlaEnvelope();
        when(slaPolicy.resolve(request.modeDecision(), request.codeGenType())).thenReturn(sla);
        when(performanceSelector.select(false, true, request.codeGenType()))
                .thenReturn(GenerationPerformanceProfile.qualityFirst());
        GenerationExecutionPlanner planner = new GenerationExecutionPlanner(
                slaPolicy, performanceSelector, contextProperties());

        GenerationExecutionPlan plan = planner.plan(request);

        assertEquals(0, plan.toolPolicy().maxInvocations());
        assertEquals(0, plan.toolPolicy().maxWriteOperations());
        assertEquals(0, plan.repairBudget().maxRounds());
        assertEquals(0, plan.sla().toLimits().limit(GenerationBudgetKind.BUILD_EXECUTION));
        assertFalse(plan.toolPolicy().writeOperationsRequireFence());
        assertFalse(plan.toolPolicy().destructiveOperationsRequireApproval());
        assertFalse(plan.repairBudget().upgradeModelProfileOnRepair());
        assertFalse(plan.commitPolicy().requireValidationSuccess());
        assertFalse(plan.commitPolicy().rollbackOnFailure());
    }

    private GenerationPipelineRequest request() {
        IntentProfile profile = new IntentProfile(
                IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.BACKEND, IntentAffectedScope.DATABASE),
                IntentSemanticComplexity.HIGH,
                true,
                true,
                IntentDestructiveRisk.HIGH,
                12,
                IntentValidationRisk.HIGH,
                0.95
        );
        GenerationModeDecision decision = GenerationModeDecision.of(
                GenerationMode.HEAVY_EXPERT,
                0.95,
                "复杂改修",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.EXPERT
        );
        return new GenerationPipelineRequest(
                null,
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                null,
                profile,
                decision
        );
    }

    private GenerationPipelineRequest readOnlyRequest() {
        IntentProfile profile = new IntentProfile(
                IntentOperationType.AUDIT,
                Set.of(IntentAffectedScope.BACKEND),
                IntentSemanticComplexity.MEDIUM,
                true,
                false,
                IntentDestructiveRisk.LOW,
                4,
                IntentValidationRisk.LOW,
                0.95
        );
        GenerationModeDecision decision = GenerationModeDecision.of(
                GenerationMode.READ_ONLY,
                0.95,
                "只读审计",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.FAST
        );
        return new GenerationPipelineRequest(
                null,
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                null,
                profile,
                decision
        );
    }

    private AiContextPackBudgetProperties contextProperties() {
        AiContextPackBudgetProperties properties = new AiContextPackBudgetProperties();
        properties.setGenerationMaxTokens(3_000);
        properties.setRepairMaxTokens(2_000);
        properties.setMaxSectionTokens(900);
        properties.setMinimumSectionTokens(80);
        properties.setMaxSemanticMemorySections(7);
        properties.setTokenizerModel("gpt-4o");
        properties.setTokenSafetyMargin(1.2);
        return properties;
    }

    private GenerationSlaEnvelope slaEnvelope() {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        budgets.put(GenerationBudgetKind.ROOT_MODEL_ATTEMPT, 2);
        budgets.put(GenerationBudgetKind.MODEL_TURN, 8);
        budgets.put(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT, 2);
        budgets.put(GenerationBudgetKind.TOOL_WRITE, 8);
        budgets.put(GenerationBudgetKind.BUILD_EXECUTION, 3);
        budgets.put(GenerationBudgetKind.REPAIR_ROUND, 2);
        return new GenerationSlaEnvelope(
                "test",
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                Duration.ofSeconds(45),
                Duration.ofSeconds(5),
                Map.copyOf(budgets),
                "测试执行计划"
        );
    }

    private GenerationSlaEnvelope readOnlySlaEnvelope() {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        budgets.put(GenerationBudgetKind.ROOT_MODEL_ATTEMPT, 1);
        budgets.put(GenerationBudgetKind.MODEL_TURN, 1);
        budgets.put(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT, 1);
        budgets.put(GenerationBudgetKind.TOOL_WRITE, 0);
        budgets.put(GenerationBudgetKind.BUILD_EXECUTION, 0);
        budgets.put(GenerationBudgetKind.REPAIR_ROUND, 0);
        return new GenerationSlaEnvelope(
                "read-only-test",
                Duration.ofSeconds(45),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                Duration.ofSeconds(10),
                Duration.ofMillis(500),
                Map.copyOf(budgets),
                "只读分析执行计划"
        );
    }
}
