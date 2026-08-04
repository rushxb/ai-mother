package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 运行同步验证、有限修复轮次以及运行时错误编辑的回滚。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LightweightRuntimeValidationService {

    private static final int MAX_RUNTIME_REPAIR_ROUNDS = 3;

    private final BackgroundValidationService backgroundValidationService;
    private final EditValidationPolicyService editValidationPolicyService;
    private final LightweightEditContextAssembler contextAssembler;
    private final LightweightEditAiService lightweightEditAiService;
    private final LightweightEditOperationConverter operationConverter;
    private final LightweightEditPatchExecutor patchExecutor;
    private final EditFileSnapshotService editFileSnapshotService;
    private final GenerationEventPublisher generationEventPublisher;

    public EditFileSnapshotService.EditFileSnapshot captureSnapshot(
            Path projectRoot,
            List<PatchOperation> patchOperations) throws PatchWorkspaceException {
        return editFileSnapshotService.capture(projectRoot, patchOperations);
    }

    /**
     * 兼容旧调用方的原始快照入口；新主链路应传递编辑事务，避免快照所有权外泄。
     */
    @Deprecated(forRemoval = false)
    public LightweightRuntimeValidationOutcome validateWithRetries(
            GenerationTaskRequest request,
            App app,
            User loginUser,
            String taskId,
            GenerationWorkspace workspace,
            String userMessage,
            String projectContext,
            EditResult editResult,
            List<PatchOperation> patchOperations,
            PatchApplyResult applyResult,
            EditValidationPlan validationPlan,
            EditFileSnapshotService.EditFileSnapshot editSnapshot,
            boolean managedModelCalls) {
        return validateWithRetries(
                request,
                app,
                loginUser,
                taskId,
                workspace,
                userMessage,
                projectContext,
                editResult,
                patchOperations,
                applyResult,
                validationPlan,
                editSnapshot,
                managedModelCalls,
                GenerationVerificationPolicy.legacy()
        );
    }

    /**
     * 兼容显式验证策略下的原始快照入口。
     */
    @Deprecated(forRemoval = false)
    public LightweightRuntimeValidationOutcome validateWithRetries(
            GenerationTaskRequest request,
            App app,
            User loginUser,
            String taskId,
            GenerationWorkspace workspace,
            String userMessage,
            String projectContext,
            EditResult editResult,
            List<PatchOperation> patchOperations,
            PatchApplyResult applyResult,
            EditValidationPlan validationPlan,
            EditFileSnapshotService.EditFileSnapshot editSnapshot,
            boolean managedModelCalls,
            GenerationVerificationPolicy verificationPolicy) {
        return validateWithRetriesInternal(
                request, app, loginUser, taskId, workspace, userMessage, projectContext,
                editResult, patchOperations, applyResult, validationPlan,
                operations -> {
                    if (editSnapshot != null) {
                        editFileSnapshotService.captureMissing(editSnapshot, operations);
                    }
                },
                managedModelCalls, verificationPolicy);
    }

    /** 按冻结计划的最低门槛，在同一编辑事务内执行验证和后续修复。 */
    public LightweightRuntimeValidationOutcome validateWithRetries(
            GenerationTaskRequest request,
            App app,
            User loginUser,
            String taskId,
            GenerationWorkspace workspace,
            String userMessage,
            String projectContext,
            EditResult editResult,
            List<PatchOperation> patchOperations,
            PatchApplyResult applyResult,
            EditValidationPlan validationPlan,
            EditWorkspaceTransaction workspaceTransaction,
            boolean managedModelCalls,
            GenerationVerificationPolicy verificationPolicy) {
        if (workspaceTransaction == null) {
            throw new IllegalArgumentException("编辑工作区事务不能为空");
        }
        return validateWithRetriesInternal(
                request, app, loginUser, taskId, workspace, userMessage, projectContext,
                editResult, patchOperations, applyResult, validationPlan,
                workspaceTransaction::include, managedModelCalls, verificationPolicy);
    }

    private LightweightRuntimeValidationOutcome validateWithRetriesInternal(
            GenerationTaskRequest request,
            App app,
            User loginUser,
            String taskId,
            GenerationWorkspace workspace,
            String userMessage,
            String projectContext,
            EditResult editResult,
            List<PatchOperation> patchOperations,
            PatchApplyResult applyResult,
            EditValidationPlan validationPlan,
            EditSnapshotScope snapshotScope,
            boolean managedModelCalls,
            GenerationVerificationPolicy verificationPolicy) {
        BackgroundValidationService.ValidationResult validationResult = executeValidation(
                taskId, app, loginUser, workspace, patchOperations, validationPlan, userMessage);
        int repairRound = 2;
        while (!validationResult.isSuccess() && repairRound <= MAX_RUNTIME_REPAIR_ROUNDS) {
            RuntimeRepairAttempt repairAttempt = retryRepair(
                    request, app, loginUser, taskId, workspace, userMessage, projectContext,
                    validationPlan, validationResult, snapshotScope, repairRound, managedModelCalls,
                    verificationPolicy);
            if (repairAttempt.unavailable()) {
                break;
            }
            validationResult = repairAttempt.validationResult();
            if (repairAttempt.success()) {
                editResult = repairAttempt.editResult();
                patchOperations = repairAttempt.patchOperations();
                applyResult = repairAttempt.applyResult();
                validationPlan = repairAttempt.validationPlan();
            }
            repairRound++;
        }
        return new LightweightRuntimeValidationOutcome(
                validationResult.isSuccess(),
                editResult,
                patchOperations,
                applyResult,
                validationPlan,
                validationResult
        );
    }
    /** 在所属任务纪元内同步执行发布门。 */
    public BackgroundValidationService.ValidationResult validateOnce(
            String taskId,
            App app,
            User loginUser,
            GenerationWorkspace workspace,
            List<PatchOperation> patchOperations,
            EditValidationPlan validationPlan,
            String userMessage) {
        return executeValidation(
                taskId, app, loginUser, workspace, patchOperations, validationPlan, userMessage);
    }

    /**
 * 返回回滚。
 *
 * @param request 请求参数
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param editSnapshot 编辑快照
 * @param projectRoot 项目根
 * @return 轻量运行时校验
 */
    public EditFileSnapshotService.RestoreResult rollback(GenerationTaskRequest request,
                                                          Long appId,
                                                          String taskId,
                                                          EditFileSnapshotService.EditFileSnapshot editSnapshot,
                                                          Path projectRoot) {
        EditFileSnapshotService.RestoreResult restoreResult =
                editFileSnapshotService.restore(taskId, editSnapshot);
        generationEventPublisher.publishSafely(request, GenerationEventType.EDIT_ROLLBACK,
                "运行时修复验证失败，已回滚本次编辑", Map.of(
                        "taskId", taskId,
                        "status", restoreResult.status(),
                        "restoredFiles", restoreResult.restoredFiles(),
                        "failedFiles", restoreResult.failedFiles()
                ));
        if (restoreResult.restored()) {
            patchExecutor.refreshIndex(projectRoot, restoreResult.restoredFiles());
            log.info("Runtime edit rolled back, appId: {}, taskId: {}, files: {}",
                    appId, taskId, restoreResult.restoredFiles().size());
        } else {
            log.warn("Runtime edit rollback incomplete, appId: {}, taskId: {}, status: {}, failedFiles: {}",
                    appId, taskId, restoreResult.status(), restoreResult.failedFiles());
        }
        return restoreResult;
    }

    /** 返回重试{@code Repair}。 */
    private RuntimeRepairAttempt retryRepair(GenerationTaskRequest request,
                                             App app,
                                             User loginUser,
                                             String taskId,
                                             GenerationWorkspace workspace,
                                             String userMessage,
                                             String projectContext,
                                             EditValidationPlan previousValidationPlan,
                                             BackgroundValidationService.ValidationResult previousValidationResult,
                                             EditSnapshotScope snapshotScope,
                                             int round,
                                             boolean managedModelCalls,
                                             GenerationVerificationPolicy verificationPolicy) {
        generationEventPublisher.publishSafely(request, GenerationEventType.REPAIR_START,
                "修复后验证失败，开始自动二次修复", Map.of(
                        "taskId", taskId,
                        "round", round,
                        "validationLevel", previousValidationPlan.level().name(),
                        "message", StrUtil.blankToDefault(previousValidationResult.message(), "")
                ));
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            String retryContext = contextAssembler.rebuildAfterValidationFailure(
                    workspace, userMessage, previousValidationResult, projectContext);
            EditResult retryEditResult = managedModelCalls
                    ? lightweightEditAiService.retryAfterValidationFailureManaged(
                    taskId, userMessage, retryContext, previousValidationResult)
                    : lightweightEditAiService.retryAfterValidationFailure(
                    userMessage, retryContext, previousValidationResult);
            if (retryEditResult == null) {
                return RuntimeRepairAttempt.unavailable(previousValidationResult);
            }
            List<PatchOperation> retryOperations = operationConverter.convert(retryEditResult.operations());
            if (retryOperations.isEmpty()) {
                return RuntimeRepairAttempt.failed(previousValidationResult);
            }
            snapshotScope.include(retryOperations);

            Path projectRoot = workspace.canonicalRootPath();
            PatchApplyResult retryApplyResult = patchExecutor.applyOnce(
                    app.getId(), taskId, projectRoot, retryOperations, "lightweight_runtime_retry");
            generationEventPublisher.publishSafely(request, GenerationEventType.PATCH_APPLY,
                    "自动二次修复补丁应用完成", Map.of(
                            "taskId", taskId,
                            "status", retryApplyResult.status(),
                            "appliedCount", retryApplyResult.appliedOperationCount(),
                            "reason", retryApplyResult.reason(),
                            "rejectedOperations", retryApplyResult.rejectedOperations()
                    ));
            if (!"applied".equals(retryApplyResult.status())) {
                return RuntimeRepairAttempt.failed(BackgroundValidationService.ValidationResult.failed(
                        taskId, "自动二次修复补丁未应用: " + patchExecutor.diagnostic(retryApplyResult)));
            }
            patchExecutor.refreshIndexIfApplied(projectRoot, retryOperations, retryApplyResult);

            EditValidationPlan retryValidationPlan = verificationPolicy.enforceEditMinimum(
                    editValidationPolicyService.determineValidationPlan(
                            retryOperations, workspace.codeGenType(), retryEditResult.validation(), userMessage));
            BackgroundValidationService.ValidationResult retryValidationResult = executeValidation(
                    taskId, app, loginUser, workspace,
                    retryOperations, retryValidationPlan, userMessage);
            if (!retryValidationResult.isSuccess()) {
                return RuntimeRepairAttempt.failed(retryValidationResult);
            }
            return RuntimeRepairAttempt.success(
                    retryEditResult,
                    retryOperations,
                    retryApplyResult,
                    retryValidationPlan,
                    retryValidationResult
            );
        } catch (GenerationExecutionPolicyException executionPolicyFailure) {
            throw executionPolicyFailure;
        } catch (Exception exception) {
            log.warn("Runtime edit repair retry failed, appId: {}, taskId: {}", app.getId(), taskId, LogExceptionSanitizer.sanitize(exception));
            return RuntimeRepairAttempt.failed(BackgroundValidationService.ValidationResult.failed(
                    taskId, "自动二次修复执行失败，请稍后重试"));
        }
    }

    private BackgroundValidationService.ValidationResult executeValidation(
            String taskId,
            App app,
            User loginUser,
            GenerationWorkspace workspace,
            List<PatchOperation> patchOperations,
            EditValidationPlan validationPlan,
            String userMessage) {
        BackgroundValidationService.ValidationResult result = backgroundValidationService.executeValidation(
                taskId, app.getId(), loginUser.getId(), workspace,
                patchOperations, validationPlan, userMessage);
        if (result == null) {
            return BackgroundValidationService.ValidationResult.failed(taskId, "验证服务未返回结果");
        }
        return result;
    }

    @FunctionalInterface
    private interface EditSnapshotScope {
        void include(List<PatchOperation> patchOperations) throws PatchWorkspaceException;
    }
    private record RuntimeRepairAttempt(
            boolean success,
            boolean unavailable,
            EditResult editResult,
            List<PatchOperation> patchOperations,
            PatchApplyResult applyResult,
            EditValidationPlan validationPlan,
            BackgroundValidationService.ValidationResult validationResult
    ) {
        private static RuntimeRepairAttempt success(
                EditResult editResult,
                List<PatchOperation> patchOperations,
                PatchApplyResult applyResult,
                EditValidationPlan validationPlan,
                BackgroundValidationService.ValidationResult validationResult) {
            return new RuntimeRepairAttempt(
                    true, false, editResult, List.copyOf(patchOperations),
                    applyResult, validationPlan, validationResult);
        }

        private static RuntimeRepairAttempt failed(
                BackgroundValidationService.ValidationResult validationResult) {
            return new RuntimeRepairAttempt(false, false, null, List.of(), null, null, validationResult);
        }

        private static RuntimeRepairAttempt unavailable(
                BackgroundValidationService.ValidationResult validationResult) {
            return new RuntimeRepairAttempt(false, true, null, List.of(), null, null, validationResult);
        }
    }
}
