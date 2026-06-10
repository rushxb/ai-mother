package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentEditVerificationService {

    private final BackgroundValidationService backgroundValidationService;
    private final EditValidationPolicyService editValidationPolicyService;
    private final AgentEditBackendValidationService backendValidationService;

    public BackgroundValidationService.ValidationResult verify(String taskId,
                                                               Long appId,
                                                               User loginUser,
                                                               GenerationWorkspace workspace,
                                                               List<PatchOperation> patchOperations,
                                                               EditChangePlan changePlan,
                                                               String userMessage) {
        if (workspace.codeGenType() == CodeGenTypeEnum.BACKEND_PROJECT) {
            return backendValidationService.validate(taskId, workspace, patchOperations);
        }
        if (workspace.codeGenType() == CodeGenTypeEnum.FULL_STACK_PROJECT && touchesOnlyBackend(patchOperations)) {
            return backendValidationService.validate(taskId, workspace, patchOperations);
        }
        EditValidationPlan validationPlan = editValidationPolicyService.determineValidationPlan(
                patchOperations,
                workspace.codeGenType(),
                null,
                userMessage
        );
        EditValidationPlan synchronousPlan = upgradeForAgentEdit(validationPlan, changePlan);
        BackgroundValidationService.ValidationResult frontendResult = backgroundValidationService.executeValidation(
                taskId,
                appId,
                loginUser == null ? null : loginUser.getId(),
                workspace,
                patchOperations,
                synchronousPlan,
                userMessage
        );
        if (workspace.codeGenType() != CodeGenTypeEnum.FULL_STACK_PROJECT || !touchesBackend(patchOperations)) {
            return frontendResult;
        }
        BackgroundValidationService.ValidationResult backendResult = backendValidationService.validate(taskId, workspace, patchOperations);
        if (!frontendResult.isSuccess()) {
            return frontendResult;
        }
        return backendResult;
    }

    private EditValidationPlan upgradeForAgentEdit(EditValidationPlan validationPlan, EditChangePlan changePlan) {
        if (validationPlan == null) {
            return new EditValidationPlan(EditValidationPlan.ValidationLevel.FAST_CHECK, "AGENT_EDIT 默认快速验证", List.of(), false);
        }
        if (changePlan != null && "build_validation".equals(changePlan.validation())
                && validationPlan.level() == EditValidationPlan.ValidationLevel.NONE) {
            return new EditValidationPlan(
                    EditValidationPlan.ValidationLevel.FAST_CHECK,
                    "AGENT_EDIT ChangePlan 要求至少执行快速验证",
                    validationPlan.changedFiles(),
                    validationPlan.aiSuggestedBuild()
            );
        }
        return validationPlan;
    }

    private boolean touchesBackend(List<PatchOperation> patchOperations) {
        return patchOperations != null && patchOperations.stream()
                .map(PatchOperation::relativePath)
                .anyMatch(this::isBackendPath);
    }

    private boolean touchesOnlyBackend(List<PatchOperation> patchOperations) {
        return patchOperations != null && !patchOperations.isEmpty() && patchOperations.stream()
                .map(PatchOperation::relativePath)
                .allMatch(this::isBackendPath);
    }

    private boolean isBackendPath(String relativePath) {
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/');
        return normalized.startsWith("backend/")
                || normalized.endsWith(".go")
                || normalized.endsWith(".sql")
                || normalized.equals("go.mod");
    }
}
