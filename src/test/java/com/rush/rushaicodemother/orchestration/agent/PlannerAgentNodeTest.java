package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationRequirementsArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
import com.rush.rushaicodemother.orchestration.intent.IntentDestructiveRisk;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity;
import com.rush.rushaicodemother.orchestration.intent.IntentValidationRisk;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerAgentNodeTest {

    @Test
    void missingFrozenScenarioMustFailClosed() {
        GenerationAgentSupport support = GenerationAgentTestFixture.support();
        PlannerAgentNode planner = new PlannerAgentNode(
                support, new GenerationRoutingSupport(support));
        App app = App.builder()
                .id(1L)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .build();
        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "调整标题",
                CodeGenTypeEnum.VUE_PROJECT,
                "update",
                false,
                ignored -> CodeGenTypeEnum.VUE_PROJECT,
                null
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-planner-missing-scenario");
        GenerationAgentContext context = new GenerationAgentContext(request, task, true);

        assertThrows(IllegalStateException.class, () -> planner.execute(context));
    }

    @Test
    void frozenHighComplexityMustSurvivePlannerPromptInterpretation() {
        GenerationAgentSupport support = GenerationAgentTestFixture.support();
        PlannerAgentNode planner = new PlannerAgentNode(
                support, new GenerationRoutingSupport(support));
        IntentProfile clarifiedProfile = new IntentProfile(
                IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.FRONTEND),
                IntentSemanticComplexity.HIGH,
                false,
                false,
                IntentDestructiveRisk.LOW,
                6,
                IntentValidationRisk.HIGH,
                0.95
        );
        GenerationModeDecision route = new GenerationModeDecision(
                GenerationMode.HEAVY_EXPERT,
                0.95,
                "澄清后确认是高复杂度改造",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.EXPERT,
                ""
        );
        GenerationScenarioDecision decision = GenerationScenarioDecision.restoreLegacy(
                clarifiedProfile,
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationResourceRequirements.none(),
                route,
                10
        );
        App app = App.builder()
                .id(1L)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .build();
        GenerationOrchestrationRequest request = GenerationOrchestrationRequest.fromFrozenScenario(
                app,
                "调整标题",
                CodeGenTypeEnum.VUE_PROJECT,
                "update",
                false,
                null,
                null,
                "task-planner-complexity",
                GenerationPlanningVariant.CURRENT_DAG,
                decision
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-planner-complexity");
        GenerationAgentContext context = new GenerationAgentContext(request, task, true);

        AgentNodeResult result = planner.execute(context);

        GenerationArtifact requirementsArtifact = result.artifacts().stream()
                .filter(artifact -> GenerationRequirementsArtifact.KEY.equals(artifact.key()))
                .findFirst()
                .orElseThrow();
        GenerationRequirementsArtifact requirements = GenerationRequirementsArtifact.fromArtifact(
                requirementsArtifact, CodeGenTypeEnum.VUE_PROJECT);
        assertTrue(requirements.complex(), "Planner 必须保留准入阶段冻结的高复杂度结论");
    }
}
