package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.artifact.ContextSummaryArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationRequirementsArtifact;
import com.rush.rushaicodemother.orchestration.artifact.ArchitecturePlan;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import com.rush.rushaicodemother.orchestration.decision.GenerationGuidanceSelection;
import com.rush.rushaicodemother.orchestration.decision.GenerationMutability;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.decision.GenerationToolPermissionProfile;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrozenGuidanceConsumptionTest {

    @Test
    void plannerAndContextMustPreserveTheSameFrozenGuidanceSnapshot() {
        List<Map<String, Object>> frozenRecipes = List.of(Map.of(
                "id", "frozen-recipe",
                "title", "冻结的工程 Recipe",
                "modules", List.of("frozen-recipe-module")
        ));
        List<Map<String, Object>> frozenSkills = List.of(Map.of(
                "id", "frozen-skill",
                "title", "冻结的工程 Skill",
                "modules", List.of("frozen-skill-module")
        ));
        GenerationGuidanceSelection guidanceSelection =
                new GenerationGuidanceSelection(frozenRecipes, frozenSkills);
        GenerationScenarioDecision decision = new GenerationScenarioDecision(
                new IntentProfile(
                        IntentOperationType.CREATE,
                        Set.of(IntentAffectedScope.FRONTEND),
                        IntentSemanticComplexity.MEDIUM,
                        false,
                        false,
                        IntentDestructiveRisk.LOW,
                        4,
                        IntentValidationRisk.MEDIUM,
                        0.95
                ),
                guidanceSelection,
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationMutability.WRITE,
                GenerationResourceRequirements.none(),
                GenerationModeDecision.of(
                        GenerationMode.HEAVY_EXPERT,
                        0.95,
                        "创建工程",
                        FallbackPolicy.NONE,
                        ExpectedValidationLevel.BUILD
                ),
                GenerationToolPermissionProfile.WRITE_FENCED,
                "intent-lexical/test",
                "release-fingerprint-test"
        );
        App app = App.builder()
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .build();
        GenerationOrchestrationRequest request = GenerationOrchestrationRequest.fromFrozenScenario(
                app,
                "创建登录注册页面",
                CodeGenTypeEnum.VUE_PROJECT,
                "create",
                false,
                null,
                null,
                "task-frozen-guidance",
                GenerationPlanningVariant.CURRENT_DAG,
                decision
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-frozen-guidance");
        GenerationAgentContext context = new GenerationAgentContext(request, task, true);
        GenerationAgentSupport support = GenerationAgentTestFixture.support();

        AgentNodeResult plannerResult = new PlannerAgentNode(support).execute(context);
        context.putArtifacts(plannerResult.artifacts());
        AgentNodeResult contextResult = new ContextAgentNode(support).execute(context);
        context.putArtifacts(contextResult.artifacts());
        AgentNodeResult architectResult = new ArchitectAgentNode().execute(context);

        GenerationRequirementsArtifact requirements = plannerResult.artifacts().stream()
                .filter(artifact -> GenerationRequirementsArtifact.KEY.equals(artifact.key()))
                .findFirst()
                .map(artifact -> GenerationRequirementsArtifact.fromArtifact(
                        artifact, CodeGenTypeEnum.VUE_PROJECT))
                .orElseThrow();
        ContextSummaryArtifact summary = contextResult.artifacts().stream()
                .filter(artifact -> ContextSummaryArtifact.KEY.equals(artifact.key()))
                .findFirst()
                .map(ContextSummaryArtifact::fromArtifact)
                .orElseThrow();
        ArchitecturePlan architecturePlan = architectResult.artifacts().stream()
                .filter(artifact -> ArchitecturePlan.KEY.equals(artifact.key()))
                .findFirst()
                .map(artifact -> ArchitecturePlan.fromArtifact(
                        artifact, CodeGenTypeEnum.VUE_PROJECT))
                .orElseThrow();

        assertEquals(guidanceSelection.recipes(), requirements.recipes());
        assertEquals(guidanceSelection.skills(), requirements.skills());
        assertEquals(guidanceSelection.recipes(), summary.recipes());
        assertEquals(guidanceSelection.skills(), summary.skills());
        assertEquals(
                List.of("frozen-recipe-module", "frozen-skill-module"),
                architecturePlan.modules()
        );
    }
}
