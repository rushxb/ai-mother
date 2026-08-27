package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.verification.GenerationValidationObservation;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 智能体编辑Verification服务实现。
 */
@Service
@RequiredArgsConstructor
public class AgentEditVerificationService {

    private final BackgroundValidationService backgroundValidationService;
    private final EditValidationPolicyService editValidationPolicyService;
    private final AgentEditBackendValidationService backendValidationService;

    /**
 * 验证智能体编辑{@code Verification}是否符合预期。
 *
 * @param taskId 任务编号
 * @param appId 应用编号
 * @param loginUser 当前登录用户
 * @param workspace 工作区
 * @param patchOperations 补丁操作
 * @param changePlan {@code changePlan} 对应的调用参数
 * @param userMessage 用户消息
 * @return 智能体编辑{@code Verification}
 */
    public AgentEditVerificationOutcome verify(String taskId,
                                                               Long appId,
                                                               User loginUser,
                                                               GenerationWorkspace workspace,
                                                               List<PatchOperation> patchOperations,
                                                               EditChangePlan changePlan,
                                                               String userMessage) {
        return verify(
                taskId,
                appId,
                loginUser,
                workspace,
                patchOperations,
                changePlan,
                userMessage,
                GenerationVerificationPolicy.legacy()
        );
    }

    /** 按冻结执行计划的最低门槛执行 AGENT_EDIT 验证。 */
    public AgentEditVerificationOutcome verify(String taskId,
                                                               Long appId,
                                                               User loginUser,
                                                               GenerationWorkspace workspace,
                                                               List<PatchOperation> patchOperations,
                                                               EditChangePlan changePlan,
                                                               String userMessage,
                                                               GenerationVerificationPolicy verificationPolicy) {
        Objects.requireNonNull(verificationPolicy, "生成验证策略不能为空");
        EditValidationPlan validationPlan = editValidationPolicyService.determineValidationPlan(
                patchOperations,
                workspace.codeGenType(),
                null,
                userMessage
        );
        EditValidationPlan synchronousPlan = verificationPolicy.enforceEditMinimum(
                upgradeForAgentEdit(validationPlan, changePlan));
        // 后端专用验证同样消费统一验证门槛，BUILD 计划必须执行真实 Go 构建。
        BackgroundValidationService.ValidationResult result;
        List<GenerationValidationObservation> observations = new ArrayList<>();
        if (workspace.codeGenType() == CodeGenTypeEnum.BACKEND_PROJECT
                || (workspace.codeGenType() == CodeGenTypeEnum.FULL_STACK_PROJECT
                && touchesOnlyBackend(patchOperations))) {
            result = backendValidationService.validate(taskId, workspace, patchOperations, synchronousPlan);
            EditValidationObservationFactory.fromBackendValidator(
                            workspace, synchronousPlan, result, "agent_edit_backend_validator")
                    .ifPresent(observations::add);
        } else {
            BackgroundValidationService.ValidationResult frontendResult =
                    backgroundValidationService.executeValidation(
                            taskId,
                            appId,
                            loginUser == null ? null : loginUser.getId(),
                            workspace,
                            patchOperations,
                            synchronousPlan,
                            userMessage
                    );
            EditValidationObservationFactory.fromBackgroundValidator(
                            workspace, synchronousPlan, frontendResult, "agent_edit_background_validator")
                    .ifPresent(observations::add);
            if (workspace.codeGenType() == CodeGenTypeEnum.FULL_STACK_PROJECT
                    && touchesBackend(patchOperations)
                    && frontendResult.isSuccess()) {
                result = backendValidationService.validate(
                        taskId, workspace, patchOperations, synchronousPlan);
                EditValidationObservationFactory.fromBackendValidator(
                                workspace, synchronousPlan, result, "agent_edit_backend_validator")
                        .ifPresent(observations::add);
            } else {
                result = frontendResult;
            }
        }
        if (result == null) {
            result = BackgroundValidationService.ValidationResult.failed(
                    taskId, "验证 implementation 未返回结果");
        }
        return AgentEditVerificationOutcome.observed(
                result,
                synchronousPlan,
                EditValidationObservationFactory.merge(
                        workspace.codeGenType(), "agent_edit_validator", observations)
                        .orElse(null),
                patchOperations == null ? 0 : patchOperations.size());
    }

    /** 返回{@code upgrade}{@code For}智能体编辑。 */
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
