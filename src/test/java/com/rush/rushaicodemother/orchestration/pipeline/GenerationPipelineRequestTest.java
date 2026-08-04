package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class GenerationPipelineRequestTest {

    @Test
    void bindingExecutionMustPreserveFrozenExecutionPlan() {
        GenerationModeDecision decision = decision(GenerationMode.AGENT_EDIT, ExpectedValidationLevel.BUILD);
        GenerationExecutionPlan plan = plan(decision);
        GenerationPipelineRequest request = new GenerationPipelineRequest(
                null, CodeGenTypeEnum.VUE_PROJECT, null, null, decision, plan, null);

        GenerationPipelineRequest executable = request.withExecution(mock(
                com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution.class));

        assertSame(plan, executable.executionPlan());
        assertSame(decision, executable.modeDecision());
    }

    @Test
    void routeFallbackMustCreateConsistentPlanWithoutChangingFrozenSla() {
        GenerationModeDecision decision = decision(GenerationMode.AGENT_EDIT, ExpectedValidationLevel.BUILD);
        GenerationExecutionPlan plan = plan(decision);
        GenerationPipelineRequest request = new GenerationPipelineRequest(
                null, CodeGenTypeEnum.VUE_PROJECT, null, null, decision, plan, null);
        GenerationModeDecision fallback = decision.withFallback(
                GenerationMode.HEAVY_EXPERT, "轻量路径失败，升级到专家路径");

        GenerationPipelineRequest fallbackRequest = request.withModeDecision(fallback);

        assertEquals(fallback, fallbackRequest.executionPlan().route());
        assertEquals(ExpectedValidationLevel.EXPERT, fallbackRequest.executionPlan().validationGraph().level());
        assertSame(plan.sla(), fallbackRequest.executionPlan().sla());
    }

    private GenerationExecutionPlan plan(GenerationModeDecision decision) {
        GenerationSlaEnvelope sla = sla();
        return new GenerationExecutionPlan(
                decision,
                GenerationPerformanceProfile.balanced(),
                new GenerationExecutionPlan.ContextBudget(2_000, 1_500, 800, 64, 6, "gpt-4o", 1.15),
                new GenerationExecutionPlan.ToolPolicy(10, 8, true, true),
                GenerationExecutionPlan.ValidationGraph.forLevel(decision.expectedValidationLevel()),
                new GenerationExecutionPlan.RepairBudget(2, true),
                new GenerationExecutionPlan.CommitPolicy(true, true),
                new GenerationExecutionPlan.PreviewPolicy(
                        sla.firstPreviewTimeout(), sla.firstPreviewCompletionReserve()),
                sla
        );
    }

    private GenerationModeDecision decision(GenerationMode mode, ExpectedValidationLevel level) {
        return GenerationModeDecision.of(
                mode, 0.9, "测试路由", FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT, level);
    }

    private GenerationSlaEnvelope sla() {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        budgets.put(GenerationBudgetKind.ROOT_MODEL_ATTEMPT, 2);
        budgets.put(GenerationBudgetKind.MODEL_TURN, 8);
        budgets.put(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT, 2);
        budgets.put(GenerationBudgetKind.TOOL_WRITE, 8);
        budgets.put(GenerationBudgetKind.BUILD_EXECUTION, 3);
        budgets.put(GenerationBudgetKind.REPAIR_ROUND, 2);
        return new GenerationSlaEnvelope(
                "test", Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofMinutes(10),
                Duration.ofSeconds(45), Duration.ofSeconds(5), Map.copyOf(budgets), "测试执行计划");
    }
}