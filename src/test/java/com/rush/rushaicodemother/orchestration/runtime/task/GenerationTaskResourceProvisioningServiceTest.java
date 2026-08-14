package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskResourceProvisioningServiceTest {

    private final AppDatabaseResourceService databaseResourceService =
            mock(AppDatabaseResourceService.class);
    private final GenerationTaskResourceProvisioningService provisioningService =
            new GenerationTaskResourceProvisioningService(databaseResourceService);

    @Test
    void shouldEnableDatabaseBetweenExecutionOwnershipChecks() {
        GenerationTaskCommand command = mock(GenerationTaskCommand.class);
        App app = new App();
        app.setId(10L);
        GenerationExecutionContext executionContext = mock(GenerationExecutionContext.class);
        GenerationScenarioDecision decision = mock(GenerationScenarioDecision.class);
        when(command.scenarioDecision()).thenReturn(decision);
        when(decision.requiredResources()).thenReturn(
                GenerationResourceRequirements.ofDatabaseRequirement(true));

        provisioningService.provision(command, app, executionContext);

        org.mockito.InOrder order = inOrder(executionContext, databaseResourceService);
        order.verify(executionContext).assertCanContinue();
        order.verify(databaseResourceService).enableDatabase(app);
        order.verify(executionContext).assertCanContinue();
    }

    @Test
    void shouldSkipDatabaseSideEffectWhenCommandDoesNotRequireIt() {
        GenerationTaskCommand command = mock(GenerationTaskCommand.class);
        App app = new App();
        GenerationExecutionContext executionContext = mock(GenerationExecutionContext.class);
        GenerationScenarioDecision decision = mock(GenerationScenarioDecision.class);
        when(command.scenarioDecision()).thenReturn(decision);
        when(decision.requiredResources()).thenReturn(GenerationResourceRequirements.none());

        provisioningService.provision(command, app, executionContext);

        verify(databaseResourceService, never()).enableDatabase(app);
        verify(executionContext, never()).assertCanContinue();
    }
}
