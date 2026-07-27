package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.ai.AiCodeEditServiceFactory;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentEditRepairServiceTest {

    @Test
    void exhaustedOptionalRepairBudgetMustReturnAnEmptyRepairAttempt() {
        GenerationEditModelInvoker modelInvoker = mock(GenerationEditModelInvoker.class);
        when(modelInvoker.invokeManagedRepair(
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);
        AgentEditPlanningService planningService = mock(AgentEditPlanningService.class);
        AgentEditRepairService service = new AgentEditRepairService(
                mock(AiCodeEditServiceFactory.class), modelInvoker, planningService);

        AgentEditRepairService.RepairAttempt result = service.repair(
                "agent-repair-budget",
                "修改标题",
                "project-context",
                BackgroundValidationService.ValidationResult.failed(
                        "agent-repair-budget", "构建失败"),
                PatchApplyResult.skipped(11L, "agent-repair-budget", "project", "validation_failed")
        );

        assertTrue(result.patchOperations().isEmpty());
        verifyNoInteractions(planningService);
    }
}
