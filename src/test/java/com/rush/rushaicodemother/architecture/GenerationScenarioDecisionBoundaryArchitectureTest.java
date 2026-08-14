package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationScenarioDecisionBoundaryArchitectureTest {

    @Test
    void pipelineAndDurableCommandMustCarryTheScenarioDecisionItself() {
        Set<Class<?>> pipelineFacts = recordComponentTypes(GenerationPipelineRequest.class);
        Set<Class<?>> commandFacts = recordComponentTypes(GenerationTaskCommand.class);

        assertTrue(pipelineFacts.contains(GenerationScenarioDecision.class));
        assertFalse(pipelineFacts.stream().anyMatch(type -> type.getSimpleName().equals("IntentProfile")));
        assertFalse(pipelineFacts.stream().anyMatch(type -> type.getSimpleName().equals("GenerationModeDecision")));
        assertTrue(commandFacts.contains(GenerationScenarioDecision.class));
        assertTrue(GenerationTaskCommand.CURRENT_SCHEMA_VERSION >= 9);
    }

    @Test
    void migratedConsumersMustReadTheDecisionInsteadOfReinterpretingPromptOrLegacyFields()
            throws IOException {
        String appService = source("service/impl/AppServiceImpl.java");
        String databaseResourceInterface = source("service/AppDatabaseResourceService.java");
        String orchestrator = source("orchestration/GenerationTaskOrchestrator.java");
        String planner = source("orchestration/plan/GenerationExecutionPlanner.java");
        String provisioning = source("orchestration/runtime/task/GenerationTaskResourceProvisioningService.java");
        String attribution = source("orchestration/learning/GenerationScenarioDecisionSnapshot.java");

        assertFalse(appService.contains("shouldEnableForPrompt"));
        assertFalse(databaseResourceInterface.contains("shouldEnableForPrompt"));
        assertTrue(orchestrator.contains("scenarioDecisionKernel.decide"));
        assertFalse(orchestrator.contains("generationModeRouter.select"));
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
