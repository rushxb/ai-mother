package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceType;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationPolicy;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentEditVerificationServiceTest {

    @Test
    void frozenBuildPlanMustRaiseDynamicFastCheckToBuildValidation() {
        BackgroundValidationService backgroundValidationService = mock(BackgroundValidationService.class);
        EditValidationPolicyService validationPolicyService = mock(EditValidationPolicyService.class);
        AgentEditBackendValidationService backendValidationService = mock(AgentEditBackendValidationService.class);
        AgentEditVerificationService service = new AgentEditVerificationService(
                backgroundValidationService,
                validationPolicyService,
                backendValidationService
        );
        GenerationWorkspace workspace = mock(GenerationWorkspace.class);
        when(workspace.codeGenType()).thenReturn(CodeGenTypeEnum.VUE_PROJECT);
        List<PatchOperation> operations = List.of(PatchOperation.modify("src/App.vue", "<template />"));
        when(validationPolicyService.determineValidationPlan(
                eq(operations), eq(CodeGenTypeEnum.VUE_PROJECT), eq(null), eq("update page")))
                .thenReturn(fastValidationPlan("src/App.vue"));
        when(backgroundValidationService.executeValidation(
                eq("agent-verification"),
                eq(11L),
                anyLong(),
                eq(workspace),
                eq(operations),
                any(EditValidationPlan.class),
                eq("update page")))
                .thenReturn(BackgroundValidationService.ValidationResult.success(
                        "agent-verification", "构建验证通过"));
        GenerationVerificationPolicy verificationPolicy = plannedBuildPolicy();

        AgentEditVerificationOutcome outcome = service.verify(
                "agent-verification",
                11L,
                User.builder().id(7L).build(),
                workspace,
                operations,
                null,
                "update page",
                verificationPolicy
        );

        ArgumentCaptor<EditValidationPlan> planCaptor = ArgumentCaptor.forClass(EditValidationPlan.class);
        verify(backgroundValidationService).executeValidation(
                eq("agent-verification"),
                eq(11L),
                eq(7L),
                eq(workspace),
                eq(operations),
                planCaptor.capture(),
                eq("update page"));
        assertEquals(EditValidationPlan.ValidationLevel.BUILD_REQUIRED, planCaptor.getValue().level());
        assertTrue(outcome.completionEvidence().contains(
                GenerationCompletionEvidenceType.FAST_VALIDATION));
        assertTrue(outcome.completionEvidence().contains(
                GenerationCompletionEvidenceType.BUILD_VALIDATION));
        assertFalse(outcome.completionEvidence().contains(
                GenerationCompletionEvidenceType.EXPERT_VALIDATION));
    }

    @Test
    void frozenBuildPlanMustReachBackendBuildValidation() {
        BackgroundValidationService backgroundValidationService = mock(BackgroundValidationService.class);
        EditValidationPolicyService validationPolicyService = mock(EditValidationPolicyService.class);
        AgentEditBackendValidationService backendValidationService = mock(AgentEditBackendValidationService.class);
        AgentEditVerificationService service = new AgentEditVerificationService(
                backgroundValidationService,
                validationPolicyService,
                backendValidationService
        );
        GenerationWorkspace workspace = mock(GenerationWorkspace.class);
        when(workspace.codeGenType()).thenReturn(CodeGenTypeEnum.BACKEND_PROJECT);
        List<PatchOperation> operations = List.of(PatchOperation.modify("cmd/server/main.go", "package main"));
        when(validationPolicyService.determineValidationPlan(
                eq(operations), eq(CodeGenTypeEnum.BACKEND_PROJECT), eq(null), eq("新增接口")))
                .thenReturn(fastValidationPlan("cmd/server/main.go"));
        when(backendValidationService.validate(
                eq("agent-backend-verification"),
                eq(workspace),
                eq(operations),
                any(EditValidationPlan.class)))
                .thenReturn(BackgroundValidationService.ValidationResult.success(
                        "agent-backend-verification", "后端构建验证通过"));

        service.verify(
                "agent-backend-verification",
                11L,
                User.builder().id(7L).build(),
                workspace,
                operations,
                null,
                "新增接口",
                plannedBuildPolicy()
        );

        ArgumentCaptor<EditValidationPlan> planCaptor = ArgumentCaptor.forClass(EditValidationPlan.class);
        verify(backendValidationService).validate(
                eq("agent-backend-verification"),
                eq(workspace),
                eq(operations),
                planCaptor.capture());
        assertEquals(EditValidationPlan.ValidationLevel.BUILD_REQUIRED, planCaptor.getValue().level());
    }

    @Test
    void backendValidatorMustNotClaimExpertReviewItDidNotPerform() {
        BackgroundValidationService backgroundValidationService = mock(BackgroundValidationService.class);
        EditValidationPolicyService validationPolicyService = mock(EditValidationPolicyService.class);
        AgentEditBackendValidationService backendValidationService = mock(AgentEditBackendValidationService.class);
        AgentEditVerificationService service = new AgentEditVerificationService(
                backgroundValidationService,
                validationPolicyService,
                backendValidationService
        );
        GenerationWorkspace workspace = mock(GenerationWorkspace.class);
        when(workspace.codeGenType()).thenReturn(CodeGenTypeEnum.BACKEND_PROJECT);
        List<PatchOperation> operations = List.of(
                PatchOperation.modify("cmd/server/main.go", "package main"));
        when(validationPolicyService.determineValidationPlan(
                eq(operations), eq(CodeGenTypeEnum.BACKEND_PROJECT), eq(null), eq("专家审查接口")))
                .thenReturn(fastValidationPlan("cmd/server/main.go"));
        when(backendValidationService.validate(
                eq("agent-backend-expert"),
                eq(workspace),
                eq(operations),
                any(EditValidationPlan.class)))
                .thenReturn(BackgroundValidationService.ValidationResult.success(
                        "agent-backend-expert", "后端构建验证通过"));

        AgentEditVerificationOutcome outcome = service.verify(
                "agent-backend-expert",
                11L,
                User.builder().id(7L).build(),
                workspace,
                operations,
                null,
                "专家审查接口",
                GenerationVerificationPolicy.planned(
                        GenerationExecutionPlan.ValidationGraph.forLevel(
                                ExpectedValidationLevel.EXPERT))
        );

        assertTrue(outcome.completionEvidence().contains(
                GenerationCompletionEvidenceType.BUILD_VALIDATION));
        assertFalse(outcome.completionEvidence().contains(
                GenerationCompletionEvidenceType.EXPERT_VALIDATION));
    }

    private EditValidationPlan fastValidationPlan(String changedFile) {
        return new EditValidationPlan(
                EditValidationPlan.ValidationLevel.FAST_CHECK,
                "动态快速验证",
                List.of(changedFile),
                false
        );
    }

    private GenerationVerificationPolicy plannedBuildPolicy() {
        return GenerationVerificationPolicy.planned(
                GenerationExecutionPlan.ValidationGraph.forLevel(ExpectedValidationLevel.BUILD));
    }
}
