package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationSpecificationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;
import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationBuildValidationService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.verification.GenerationValidationObservation;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationEvidenceRecorder;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationPolicy;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspace;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 创建Post生成校验服务实现。
 */
@Service
@RequiredArgsConstructor
public class CreatePostGenerationValidationService {

    private final GenerationToolExecutionContextService generationToolExecutionContextService;
    private final HeavyGenerationBuildValidationService heavyGenerationBuildValidationService;

    /**
 * 校验{@code ate}是否有效。
 *
 * @param appId 应用编号
 * @param loginUser 当前登录用户
 * @param codeGenType 代码生成类型
 * @param userMessage 用户消息
 * @param taskId 任务编号
 * @param result 待处理结果
 * @param session 会话
 * @return {@code ate}
 */
    public ValidationOutcome validate(Long appId,
                                      User loginUser,
                                      CodeGenTypeEnum codeGenType,
                                      String userMessage,
                                      String taskId,
                                      SlotFillResult result,
                                      GenerationSession session) {
        return validate(
                appId,
                loginUser,
                codeGenType,
                userMessage,
                taskId,
                result,
                session,
                null
        );
    }

    /** 按任务提交时冻结的验证计划执行 CREATE 生成后门禁。 */
    public ValidationOutcome validate(Long appId,
                                      User loginUser,
                                      CodeGenTypeEnum codeGenType,
                                      String userMessage,
                                      String taskId,
                                      SlotFillResult result,
                                      GenerationSession session,
                                      GenerationExecutionPlan executionPlan) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT
                && codeGenType != CodeGenTypeEnum.BACKEND_PROJECT
                && codeGenType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return ValidationOutcome.skipped("non_project_create");
        }
        GenerationVerificationPolicy verificationPolicy = GenerationVerificationPolicy.resolve(
                executionPlan,
                ExpectedValidationLevel.BUILD
        );
        GenerationPreparation preparation = verificationPolicy.enforceValidationFloor(
                createRepairPreparation(codeGenType, userMessage, taskId, result));
        GenerationExecutionFence executionFence = executionFence(session);
        GenerationExecutionWorkspace executionWorkspace = session == null
                ? null
                : session.executionWorkspace();
        bindRepairContext(appId, codeGenType, taskId, result, executionFence, executionWorkspace);
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            session.emit(GenerationStreamEvent.generationStage("CREATE 模板生成完成，正在执行构建验证...", Map.of(
                    "stage", AppConstant.GENERATING_STAGE_BUILD,
                    "taskId", taskId,
                    "route", "create"
            )));
            boolean passed = executionPlan == null
                    ? heavyGenerationBuildValidationService.runWithAutoRepair(
                            appId, loginUser, preparation, session)
                    : heavyGenerationBuildValidationService.runWithAutoRepair(
                            appId, loginUser, preparation, session, verificationPolicy);
            if (!passed) {
                return ValidationOutcome.failed("create_post_generation_validation_failed");
            }
            return GenerationVerificationEvidenceRecorder.latestObservation(preparation)
                    .filter(observation -> observation.targetType() == codeGenType)
                    .map(ValidationOutcome::passed)
                    .orElseGet(() -> ValidationOutcome.failed("create_validation_evidence_missing"));
        } finally {
            if (executionFence == null) {
                generationToolExecutionContextService.clearContext(appId, taskId);
            } else {
                generationToolExecutionContextService.clearContext(appId, taskId, executionFence);
            }
        }
    }

    /** 创建{@code Repair}{@code Preparation}。 */
    private GenerationPreparation createRepairPreparation(CodeGenTypeEnum codeGenType,
                                                          String userMessage,
                                                          String taskId,
                                                          SlotFillResult result) {
        Map<String, GenerationArtifact> artifacts = new LinkedHashMap<>();
        artifacts.put(
                GenerationSpecificationArtifact.KEY,
                GenerationSpecificationArtifact
                        .postGenerationValidation(true)
                        .toArtifact("CREATE", "CREATE 模板生成后验证规范")
        );
        artifacts.put("change_plan", GenerationArtifact.of(
                "change_plan",
                "CREATE",
                "CREATE 模板生成后修复边界",
                changePlan(result).toPayload()
        ));
        return new GenerationPreparation(
                codeGenType,
                codeGenType,
                false,
                AppConstant.GENERATING_STAGE_CREATE,
                "【CREATE 构建修复任务】\n" + userMessage,
                List.of(GenerationStreamEvent.agentEvent("CREATE 模板生成后验证", Map.of(
                        "orchestrationMode", "create_post_generation_validation",
                        "route", "create",
                        "taskId", taskId
                ))),
                artifacts,
                QualityGateResult.passed(List.of(), List.of("create_template_generated")),
                Map.of(),
                taskId
        );
    }

    /** 返回{@code change}计划。 */
    private ChangePlan changePlan(SlotFillResult result) {
        List<String> touchedFiles = result == null || result.patchOperations() == null
                ? List.of()
                : result.patchOperations().stream()
                .map(PatchOperation::relativePath)
                .distinct()
                .toList();
        return new ChangePlan(
                "v1",
                "create_build_repair",
                List.of(),
                touchedFiles,
                List.of(),
                List.of("create_template"),
                "build_validation",
                "manual_retry_without_snapshot"
        );
    }

    /** 绑定{@code Repair}上下文。 */
    private void bindRepairContext(Long appId,
                                   CodeGenTypeEnum codeGenType,
                                   String taskId,
                                   SlotFillResult result,
                                   GenerationExecutionFence executionFence,
                                   GenerationExecutionWorkspace executionWorkspace) {
        generationToolExecutionContextService.bindChangePlan(
                appId,
                taskId,
                "create_build_repair",
                codeGenType,
                changePlan(result),
                true,
                "create_post_generation_build_repair",
                executionWorkspace == null ? null : executionWorkspace.workspace(),
                executionFence
        );
    }

    private GenerationExecutionFence executionFence(GenerationSession session) {
        return session == null || session.executionContext() == null
                ? null
                : session.executionContext().executionFence();
    }

    public record ValidationOutcome(
            boolean success,
            boolean executed,
            String reason,
            GenerationValidationObservation observation
    ) {

        public ValidationOutcome {
            if (success && executed && observation == null) {
                throw new IllegalArgumentException("已执行成功的 CREATE 验证必须携带实际观测");
            }
            if (!success && observation != null) {
                throw new IllegalArgumentException("失败的 CREATE 验证不能携带通过观测");
            }
        }

        /** 兼容失败和跳过结果；成功结果必须通过 {@link #passed} 携带事实证据。 */
        public ValidationOutcome(boolean success, boolean executed, String reason) {
            this(success, executed, reason, null);
        }

        private static ValidationOutcome passed(GenerationValidationObservation observation) {
            return new ValidationOutcome(true, true, "", observation);
        }

        private static ValidationOutcome failed(String reason) {
            return new ValidationOutcome(false, true, reason, null);
        }

        private static ValidationOutcome skipped(String reason) {
            return new ValidationOutcome(true, false, reason, null);
        }
    }
}
