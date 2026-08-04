package com.rush.rushaicodemother.orchestration.plan;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationExecutionPlanTest {

    @Test
    void executionPlanMustKeepEveryExecutionConstraintImmutable() {
        List<GenerationExecutionPlan.ValidationStep> mutableSteps = new ArrayList<>(List.of(
                GenerationExecutionPlan.ValidationStep.FAST_CHECK,
                GenerationExecutionPlan.ValidationStep.BUILD
        ));
        GenerationSlaEnvelope sla = slaEnvelope();

        GenerationExecutionPlan plan = new GenerationExecutionPlan(
                routeDecision(),
                GenerationPerformanceProfile.balanced(),
                new GenerationExecutionPlan.ContextBudget(
                        2_000, 1_500, 800, 64, 6, "gpt-4o", 1.15),
                new GenerationExecutionPlan.ToolPolicy(10, 8, true, true),
                new GenerationExecutionPlan.ValidationGraph(
                        ExpectedValidationLevel.BUILD, mutableSteps),
                new GenerationExecutionPlan.RepairBudget(2, true),
                new GenerationExecutionPlan.CommitPolicy(true, true),
                new GenerationExecutionPlan.PreviewPolicy(
                        sla.firstPreviewTimeout(), sla.firstPreviewCompletionReserve()),
                sla
        );
        mutableSteps.clear();

        assertEquals(List.of(
                GenerationExecutionPlan.ValidationStep.FAST_CHECK,
                GenerationExecutionPlan.ValidationStep.BUILD
        ), plan.validationGraph().steps());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.validationGraph().steps().add(GenerationExecutionPlan.ValidationStep.EXPERT_CHECK));
        assertEquals(2, plan.repairBudget().maxRounds());
        assertEquals(8, plan.toolPolicy().maxWriteOperations());
    }

    @Test
    void executionPlanMustRejectValidationGraphThatDriftsFromRoute() {
        GenerationSlaEnvelope sla = slaEnvelope();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new GenerationExecutionPlan(
                        routeDecision(),
                        GenerationPerformanceProfile.balanced(),
                        new GenerationExecutionPlan.ContextBudget(
                                2_000, 1_500, 800, 64, 6, "gpt-4o", 1.15),
                        new GenerationExecutionPlan.ToolPolicy(10, 8, true, true),
                        GenerationExecutionPlan.ValidationGraph.forLevel(ExpectedValidationLevel.FAST),
                        new GenerationExecutionPlan.RepairBudget(2, true),
                        new GenerationExecutionPlan.CommitPolicy(true, true),
                        new GenerationExecutionPlan.PreviewPolicy(
                                sla.firstPreviewTimeout(), sla.firstPreviewCompletionReserve()),
                        sla
                ));

        assertEquals("执行计划验证等级必须与路由决策一致", exception.getMessage());
    }

    @Test
    void executionPlanMustRejectRepairBudgetThatDriftsFromSla() {
        GenerationSlaEnvelope sla = slaEnvelope();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> createPlan(
                        sla,
                        new GenerationExecutionPlan.ToolPolicy(10, 8, true, true),
                        new GenerationExecutionPlan.RepairBudget(1, true),
                        new GenerationExecutionPlan.PreviewPolicy(
                                sla.firstPreviewTimeout(), sla.firstPreviewCompletionReserve())));

        assertEquals("执行计划修复预算必须与 SLA 一致", exception.getMessage());
    }

    @Test
    void executionPlanMustRejectToolWriteBudgetThatDriftsFromSla() {
        GenerationSlaEnvelope sla = slaEnvelope();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> createPlan(
                        sla,
                        new GenerationExecutionPlan.ToolPolicy(10, 7, true, true),
                        new GenerationExecutionPlan.RepairBudget(2, true),
                        new GenerationExecutionPlan.PreviewPolicy(
                                sla.firstPreviewTimeout(), sla.firstPreviewCompletionReserve())));

        assertEquals("执行计划写工具预算必须与 SLA 一致", exception.getMessage());
    }

    @Test
    void executionPlanMustRejectPreviewPolicyThatDriftsFromSla() {
        GenerationSlaEnvelope sla = slaEnvelope();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> createPlan(
                        sla,
                        new GenerationExecutionPlan.ToolPolicy(10, 8, true, true),
                        new GenerationExecutionPlan.RepairBudget(2, true),
                        new GenerationExecutionPlan.PreviewPolicy(
                                sla.firstPreviewTimeout().plusSeconds(1),
                                sla.firstPreviewCompletionReserve())));

        assertEquals("执行计划预览策略必须与 SLA 一致", exception.getMessage());
    }

    private GenerationExecutionPlan createPlan(
            GenerationSlaEnvelope sla,
            GenerationExecutionPlan.ToolPolicy toolPolicy,
            GenerationExecutionPlan.RepairBudget repairBudget,
            GenerationExecutionPlan.PreviewPolicy previewPolicy) {
        return new GenerationExecutionPlan(
                routeDecision(),
                GenerationPerformanceProfile.balanced(),
                new GenerationExecutionPlan.ContextBudget(
                        2_000, 1_500, 800, 64, 6, "gpt-4o", 1.15),
                toolPolicy,
                GenerationExecutionPlan.ValidationGraph.forLevel(ExpectedValidationLevel.BUILD),
                repairBudget,
                new GenerationExecutionPlan.CommitPolicy(true, true),
                previewPolicy,
                sla
        );
    }

    private GenerationModeDecision routeDecision() {
        return GenerationModeDecision.of(
                GenerationMode.AGENT_EDIT,
                0.9,
                "测试路由",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD
        );
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
}