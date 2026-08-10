package com.rush.rushaicodemother.orchestration.intent;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceSelector;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.context.AiContextPackBudgetProperties;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlanner;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.DefaultGenerationSlaPolicy;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaProperties;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 澄清阶段回归。
 *
 * <p>核心断言只有一条：澄清可以抬高模型档位，但绝不能改动提交阶段冻结的
 * 路由、SLA、验证图与写工具预算。</p>
 */
class IntentClarificationStageTest {

    private final GenerationExecutionPlanner planner = new GenerationExecutionPlanner(
            new DefaultGenerationSlaPolicy(new GenerationSlaProperties()),
            new GenerationPerformanceSelector(),
            new AiContextPackBudgetProperties()
    );

    @Test
    void refinedComplexityMustOnlyReplaceModelProfile() {
        GenerationPipelineRequest request = request(IntentSemanticComplexity.LOW);
        GenerationExecutionPlan frozenPlan = request.executionPlan();
        IntentProfile refinedProfile = request.intentProfile()
                .withAmbiguitySignal(IntentAmbiguitySignal.resolved());
        // 复杂度由 LOW 抬到 HIGH，等价于"看起来简单、实际复杂"的澄清结果。
        IntentProfile escalatedProfile = new IntentProfile(
                refinedProfile.operationType(),
                refinedProfile.affectedScopes(),
                IntentSemanticComplexity.HIGH,
                refinedProfile.requiresBackend(),
                refinedProfile.requiresDatabase(),
                refinedProfile.destructiveRisk(),
                refinedProfile.expectedFileCount(),
                refinedProfile.validationRisk(),
                refinedProfile.confidence()
        );

        GenerationExecutionPlan refinedPlan = planner.replanModelProfile(
                frozenPlan, escalatedProfile, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals(frozenPlan.sla(), refinedPlan.sla(), "SLA 必须保持冻结");
        assertEquals(frozenPlan.route(), refinedPlan.route(), "路由必须保持不变");
        assertEquals(frozenPlan.validationGraph(), refinedPlan.validationGraph(), "验证图必须保持不变");
        assertEquals(frozenPlan.repairBudget(), refinedPlan.repairBudget(), "修复预算必须保持不变");
        assertEquals(frozenPlan.toolPolicy().maxWriteOperations(),
                refinedPlan.toolPolicy().maxWriteOperations(), "写工具预算必须保持不变");
    }

    @Test
    void unchangedProfileMustReuseFrozenPlan() {
        GenerationPipelineRequest request = request(IntentSemanticComplexity.MEDIUM);

        GenerationExecutionPlan refinedPlan = planner.replanModelProfile(
                request.executionPlan(), request.intentProfile(), CodeGenTypeEnum.VUE_PROJECT);

        assertSame(request.executionPlan(), refinedPlan, "档位未变时不应产生新计划");
    }

    @Test
    void refinedRequestMustRejectRouteChange() {
        GenerationPipelineRequest request = request(IntentSemanticComplexity.MEDIUM);
        GenerationExecutionPlan reroutedPlan = request.executionPlan()
                .withRoute(GenerationModeDecision.of(
                        GenerationMode.HEAVY_EXPERT,
                        0.9,
                        "试图借澄清改路由",
                        FallbackPolicy.NONE,
                        ExpectedValidationLevel.EXPERT
                ));

        assertThrows(IllegalArgumentException.class,
                () -> request.withRefinedIntent(request.intentProfile(), reroutedPlan),
                "澄清不得改变流水线路由决策");
    }

    private GenerationPipelineRequest request(IntentSemanticComplexity complexity) {
        App app = App.builder()
                .id(10L)
                .userId(20L)
                .tenantId(1L)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .build();
        User user = User.builder().id(20L).build();
        IntentProfile profile = new IntentProfile(
                IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.FRONTEND),
                complexity,
                false,
                false,
                IntentDestructiveRisk.LOW,
                2,
                IntentValidationRisk.LOW,
                0.8
        );
        GenerationModeDecision decision = GenerationModeDecision.of(
                GenerationMode.AGENT_EDIT,
                0.8,
                "clarification-stage-test",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD
        );
        GenerationPipelineRequest planless = new GenerationPipelineRequest(
                new GenerationTaskRequest(app, "把登录页做得好看点", user),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(),
                profile,
                decision
        );
        return planless.withExecutionPlan(planner.plan(planless));
    }

    private GenerationWorkspace workspace() {
        Path root = Path.of("target/test-clarification-stage-workspace");
        return new GenerationWorkspace(
                10L,
                CodeGenTypeEnum.VUE_PROJECT,
                root,
                root,
                true,
                root,
                root,
                Set.of(),
                Set.of()
        );
    }
}
