package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.decision.GenerationPreflightUsage;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingSignal;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationScenarioDecisionBoundaryArchitectureTest {

    @Test
    void orchestrationRequestMustNotCarryASecondRoutingFunction() {
        Set<Class<?>> requestFacts = recordComponentTypes(
                com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest.class);

        assertTrue(requestFacts.contains(GenerationScenarioDecision.class));
        assertFalse(requestFacts.contains(Function.class),
                "编排请求只能消费冻结场景决策，不能保留第二套路由函数");
    }

    @Test
    void pipelineAndDurableCommandMustCarryTheScenarioDecisionItself() {
        Set<Class<?>> pipelineFacts = recordComponentTypes(GenerationPipelineRequest.class);
        Set<Class<?>> commandFacts = recordComponentTypes(GenerationTaskCommand.class);

        assertTrue(pipelineFacts.contains(GenerationScenarioDecision.class));
        assertFalse(pipelineFacts.stream().anyMatch(type -> type.getSimpleName().equals("IntentProfile")));
        assertFalse(pipelineFacts.stream().anyMatch(type -> type.getSimpleName().equals("GenerationModeDecision")));
        assertTrue(commandFacts.contains(GenerationScenarioDecision.class));
        assertTrue(commandFacts.contains(GenerationPreflightUsage.class));
        assertTrue(GenerationTaskCommand.CURRENT_SCHEMA_VERSION >= 10);
    }

    @Test
    void productionRoutingSignalMustNotExposeTheRawRequestOrPrompt() {
        Set<Class<?>> routingFacts = recordComponentTypes(GenerationRoutingSignal.class);

        assertFalse(routingFacts.stream().anyMatch(type -> type.getSimpleName()
                .equals("GenerationTaskRequest")));
        assertFalse(routingFacts.contains(String.class));
    }

    @Test
    void migratedConsumersMustReadTheDecisionInsteadOfReinterpretingPromptOrLegacyFields()
            throws IOException {
        String appService = source("service/impl/AppServiceImpl.java");
        String databaseResourceInterface = source("service/AppDatabaseResourceService.java");
        String orchestrator = source("orchestration/GenerationTaskOrchestrator.java");
        String submission = source("orchestration/runtime/task/GenerationTaskSubmissionService.java");
        String preflight = source("orchestration/decision/GenerationScenarioPreflight.java");
        String clarificationStage = source("orchestration/intent/IntentClarificationStage.java");
        String planner = source("orchestration/plan/GenerationExecutionPlanner.java");
        String provisioning = source("orchestration/runtime/task/GenerationTaskResourceProvisioningService.java");
        String attribution = source("orchestration/learning/GenerationScenarioDecisionSnapshot.java");

        assertFalse(appService.contains("shouldEnableForPrompt"));
        assertFalse(databaseResourceInterface.contains("shouldEnableForPrompt"));
        assertFalse(orchestrator.contains("scenarioDecisionKernel"));
        assertFalse(orchestrator.contains("generationModeRouter.select"));
        assertTrue(orchestrator.contains("generationTaskSubmissionService.submit("));
        assertTrue(submission.indexOf("findIdempotentReplay(")
                < submission.indexOf("scenarioPreflight.prepare("));
        assertTrue(submission.indexOf("scenarioPreflight.prepare(")
                < submission.indexOf("generationExecutionPlanner.plan("));
        assertTrue(preflight.contains("admissionService.assertMayPreflight"));
        assertTrue(preflight.contains("GenerationBudgetKind.ROOT_MODEL_ATTEMPT, 1"));
        assertTrue(preflight.contains("GenerationBudgetKind.MODEL_TURN, 1"));
        assertFalse(clarificationStage.contains("clarificationRefiner.refine"));
        assertTrue(planner.contains("request.scenarioDecision()"));
        assertFalse(planner.contains("request.intentProfile()"));
        assertFalse(planner.contains("request.modeDecision()"));
        assertTrue(provisioning.contains("command.scenarioDecision().requiredResources()"));
        assertFalse(provisioning.contains("command.resourceRequirements()"));
        assertTrue(attribution.contains("command.scenarioDecision()"));
        assertFalse(attribution.contains("intent-profile-v1"));
        assertFalse(attribution.contains("routing-policy-v1"));
    }

    private Set<Class<?>> recordComponentTypes(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getType)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String source(String relativePath) throws IOException {
        return Files.readString(Path.of("src/main/java/com/rush/rushaicodemother", relativePath));
    }
}
