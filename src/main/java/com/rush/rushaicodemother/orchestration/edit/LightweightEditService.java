package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.attempt.completion.ObservedValidationCompletionEvidenceFactory;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationPolicy;
import com.rush.rushaicodemother.orchestration.verification.GenerationValidationObservation;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 协调轻量级编辑工作流程，同时委派专门职责。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LightweightEditService {

    private final GenerationEditRouteService generationEditRouteService;
    private final LightweightEditContextAssembler contextAssembler;
    private final LightweightEditAiService lightweightEditAiService;
    private final LightweightEditOperationConverter operationConverter;
    private final LightweightEditPatchExecutor patchExecutor;
    private final LightweightRuntimeValidationService runtimeValidationService;
    private final GenerationEventPublisher generationEventPublisher;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final LightweightEditTaskLifecycleService taskLifecycleService;
    private final ChatHistoryService chatHistoryService;
    private final EditValidationPolicyService editValidationPolicyService;
    private final EditStatePersistenceService editStatePersistenceService;
    private final EditFileSnapshotService editFileSnapshotService;

    /**
     * 执行轻量级编辑，或者当请求必须使用重度路由时返回 {@code null}。
     */
    @Deprecated(forRemoval = false)
    public LightweightEditResult execute(GenerationTaskRequest request) {
        if (request == null || request.app() == null) {
            return null;
        }
        App app = request.app();
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        return executeInternal(
                generateTaskId(), request,
                generationWorkspaceService.resolve(app, codeGenType), false, null);
    }

    /** 使用提交运行时分配的任务标识执行轻量级编辑。 */
    public LightweightEditResult execute(String taskId, GenerationTaskRequest request) {
        if (request == null || request.app() == null) {
            return null;
        }
        App app = request.app();
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        return execute(taskId, request, generationWorkspaceService.resolve(app, codeGenType));
    }

    /** 针对持久工作人员选择的确切工作空间执行轻量级编辑。 */
    public LightweightEditResult execute(String taskId,
                                         GenerationTaskRequest request,
                                         GenerationWorkspace workspace) {
        return execute(taskId, request, workspace, null);
    }

    /** 针对持久工作进程冻结的执行计划运行轻量编辑。 */
    public LightweightEditResult execute(String taskId,
                                         GenerationTaskRequest request,
                                         GenerationWorkspace workspace,
                                         GenerationExecutionPlan executionPlan) {
        return executeInternal(taskId, request, workspace, true, executionPlan);
    }

    /** 执行内部处理流程。 */
    private LightweightEditResult executeInternal(String taskId,
                                                   GenerationTaskRequest request,
                                                   GenerationWorkspace workspace,
                                                   boolean managedModelCalls,
                                                   GenerationExecutionPlan executionPlan) {
        requireTaskId(taskId);
        if (request == null || request.app() == null || request.loginUser() == null) {
            return null;
        }

        App app = request.app();
        User loginUser = request.loginUser();
        String userMessage = request.message();
        GenerationEditRouteResult routeResult = generationEditRouteService.route(app, userMessage, workspace);
        if (!routeResult.isLightweightEdit()) {
            log.info("Route selected heavy generation, appId: {}, reason: {}", app.getId(), routeResult.reason());
            return null;
        }

        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        GenerationVerificationPolicy verificationPolicy = GenerationVerificationPolicy.resolve(
                executionPlan,
                ExpectedValidationLevel.FAST
        );
        if (codeGenType == null) {
            return null;
        }
        requireWorkspace(app, codeGenType, workspace);
        if (workspace == null || !workspace.exists()) {
            return null;
        }

        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            publishRouteEvent(request, taskId, routeResult);
            taskLifecycleService.start(
                    taskId, app, loginUser, codeGenType, userMessage, routeResult.requiresBuild());

            LightweightEditContext editContext = contextAssembler.assemble(workspace, userMessage);
            if (!editContext.hasCandidates()) {
                generationEventPublisher.publishSafely(request, GenerationEventType.TASK_FAILED,
                        "文件定位未找到候选文件", Map.of(
                                "taskId", taskId,
                                "reason", "no_candidates_found"
                        ));
                return buildFailedResult(taskId, "文件定位未找到候选文件");
            }
            publishLocatorEvent(request, taskId, editContext.candidates());
            if (!editContext.contextAvailable()) {
                generationEventPublisher.publishSafely(request, GenerationEventType.TASK_FAILED,
                        "轻量编辑上下文构建为空", Map.of(
                                "taskId", taskId,
                                "reason", "edit_context_empty"
                        ));
                return buildFailedResult(taskId, "上下文构建为空");
            }

            EditResult editResult = generateInitialEdit(
                    app, taskId, userMessage, editContext.projectContext(), managedModelCalls);
            if (editResult == null || editResult.operations() == null || editResult.operations().isEmpty()) {
                return buildFailedResult(taskId, "AI 编辑返回空操作");
            }

            List<PatchOperation> patchOperations = operationConverter.convert(editResult.operations());
            if (patchOperations.isEmpty()) {
                return buildFailedResult(taskId, "无有效补丁操作");
            }

            Path projectRoot = workspace.canonicalRootPath();
            boolean runtimeErrorRepair = editValidationPolicyService.isRuntimeErrorRepairRequest(userMessage);
            try (EditWorkspaceTransaction workspaceTransaction = editFileSnapshotService.beginTransaction(
                    taskId, projectRoot, patchOperations)) {
                LightweightEditAttempt editAttempt = patchExecutor.applyWithRetry(
                        request,
                        app.getId(),
                        taskId,
                        projectRoot,
                        userMessage,
                        editContext.projectContext(),
                        editResult,
                        patchOperations,
                        workspaceTransaction,
                        managedModelCalls
                );
                editResult = editAttempt.editResult();
                patchOperations = editAttempt.patchOperations();
                PatchApplyResult applyResult = editAttempt.applyResult();
                publishPatchResult(request, taskId, applyResult);
                patchExecutor.refreshIndexIfApplied(projectRoot, patchOperations, applyResult);

                EditValidationPlan validationPlan = verificationPolicy.enforceEditMinimum(
                        editValidationPolicyService.determineValidationPlan(
                                patchOperations, codeGenType, editResult.validation(), userMessage));
                GenerationValidationObservation validationObservation = null;
                boolean editSuccess = applyResult != null && "applied".equals(applyResult.status());
                if (!runtimeErrorRepair) {
                    editStatePersistenceService.recordEditResult(
                            app.getId(), taskId, patchOperations, editSuccess);
                }

                if (validationPlan.requiresBackgroundValidation() && editSuccess && runtimeErrorRepair) {
                    LightweightRuntimeValidationOutcome validationOutcome = runtimeValidationService.validateWithRetries(
                            request,
                            app,
                            loginUser,
                            taskId,
                            workspace,
                            userMessage,
                            editContext.projectContext(),
                            editResult,
                            patchOperations,
                            applyResult,
                            validationPlan,
                            workspaceTransaction,
                            managedModelCalls,
                            verificationPolicy
                    );
                    editResult = validationOutcome.editResult();
                    patchOperations = validationOutcome.patchOperations();
                    applyResult = validationOutcome.applyResult();
                    validationPlan = validationOutcome.validationPlan();
                    if (!validationOutcome.success()) {
                        return handleRuntimeValidationFailure(
                                request,
                                app,
                                loginUser,
                                taskId,
                                patchOperations,
                                validationPlan,
                                validationOutcome.validationResult(),
                                workspaceTransaction,
                                projectRoot
                        );
                    }
                    validationObservation = EditValidationObservationFactory.fromBackgroundValidator(
                                    workspace,
                                    validationPlan,
                                    validationOutcome.validationResult(),
                                    "lightweight_edit_validator")
                            .orElse(null);
                    editStatePersistenceService.recordEditResult(
                            app.getId(), taskId, patchOperations, true);
                } else if (validationPlan.requiresBackgroundValidation() && editSuccess) {
                    BackgroundValidationService.ValidationResult validationResult =
                            runtimeValidationService.validateOnce(
                            taskId, app, loginUser, workspace, patchOperations, validationPlan, userMessage);
                    if (!validationResult.isSuccess()) {
                        return handlePublicationValidationFailure(
                                request, app, loginUser, taskId, patchOperations,
                                validationPlan, validationResult, workspaceTransaction, projectRoot);
                    }
                    validationObservation = EditValidationObservationFactory.fromBackgroundValidator(
                                    workspace,
                                    validationPlan,
                                    validationResult,
                                    "lightweight_edit_validator")
                            .orElse(null);
                }

                if (!editSuccess) {
                    return handlePatchFailure(
                            request, app, loginUser, taskId, userMessage,
                            patchOperations, applyResult, runtimeErrorRepair, workspaceTransaction, projectRoot);
                }
                LightweightEditResult result = completeSuccess(
                        request, app, loginUser, taskId, editResult, applyResult,
                        validationPlan, validationObservation);
                workspaceTransaction.commit();
                return result;
            }
        } catch (GenerationExecutionPolicyException executionPolicyFailure) {
            throw executionPolicyFailure;
        } catch (Exception exception) {
            log.error("Lightweight edit failed, appId: {}, taskId: {}", app.getId(), taskId, LogExceptionSanitizer.sanitize(exception));
            GenerationErrorClassifier.GenerationError publicError = GenerationErrorClassifier.classify(exception);
            generationEventPublisher.publishSafely(request, GenerationEventType.TASK_FAILED,
                    "轻量编辑执行失败", Map.of(
                            "taskId", taskId,
                            "category", StrUtil.blankToDefault(publicError.category(), "unknown"),
                            "error", StrUtil.blankToDefault(publicError.message(), "轻量编辑执行失败")
                    ));
            return buildFailedResult(taskId, "轻量编辑执行失败，请稍后重试");
        }
    }

    /** 根据输入生成{@code Initial}编辑。 */
    private EditResult generateInitialEdit(App app,
                                           String taskId,
                                           String userMessage,
                                           String projectContext,
                                           boolean managedModelCall) {
        try {
            return managedModelCall
                    ? lightweightEditAiService.generateManaged(taskId, userMessage, projectContext)
                    : lightweightEditAiService.generate(userMessage, projectContext);
        } catch (Exception exception) {
            log.error("AI edit model call failed, appId: {}, taskId: {}", app.getId(), taskId, LogExceptionSanitizer.sanitize(exception));
            throw exception;
        }
    }

    /** 处理运行时校验失败，并将整个编辑事务恢复到首次修改前。 */
    private LightweightEditResult handleRuntimeValidationFailure(
            GenerationTaskRequest request,
            App app,
            User loginUser,
            String taskId,
            List<PatchOperation> patchOperations,
            EditValidationPlan validationPlan,
            BackgroundValidationService.ValidationResult validationResult,
            EditWorkspaceTransaction workspaceTransaction,
            Path projectRoot) {
        EditFileSnapshotService.RestoreResult restoreResult = rollbackWorkspace(
                request, taskId, "运行时修复验证失败，已回滚本次编辑",
                workspaceTransaction, projectRoot);
        String validationMessage = validationResult == null
                ? "验证服务未返回结果"
                : StrUtil.blankToDefault(validationResult.message(), "验证未通过");
        String failedMessage = "修复后验证未通过: " + validationMessage;
        chatHistoryService.addChatMessage(
                app.getId(), failedMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
        editStatePersistenceService.recordEditResult(
                app.getId(), taskId, patchOperations, false);
        generationEventPublisher.publishSafely(request, GenerationEventType.TASK_FAILED,
                "轻量编辑验证未通过", Map.of(
                        "taskId", taskId,
                        "status", validationResult == null ? "failed" : validationResult.status(),
                        "message", validationMessage,
                        "validationLevel", validationPlan == null ? "unknown" : validationPlan.level().name(),
                        "rollbackStatus", restoreResult.status()
                ));
        return buildFailedResult(taskId, failedMessage);
    }

    /** 处理补丁失败，确保重试过程中产生的部分修改不会泄漏到工作区。 */
    private LightweightEditResult handlePatchFailure(GenerationTaskRequest request,
                                                     App app,
                                                     User loginUser,
                                                     String taskId,
                                                     String userMessage,
                                                     List<PatchOperation> patchOperations,
                                                     PatchApplyResult applyResult,
                                                     boolean runtimeErrorRepair,
                                                     EditWorkspaceTransaction workspaceTransaction,
                                                     Path projectRoot) {
        EditFileSnapshotService.RestoreResult restoreResult = rollbackWorkspace(
                request, taskId, "补丁未成功应用，已恢复事务开始前状态",
                workspaceTransaction, projectRoot);
        String failedMessage = "补丁未应用: " + patchExecutor.diagnostic(applyResult);
        if (runtimeErrorRepair) {
            editStatePersistenceService.recordEditResult(
                    app.getId(), taskId, patchOperations, false);
        }
        chatHistoryService.addChatMessage(
                app.getId(), failedMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
        generationEventPublisher.publishSafely(request, GenerationEventType.TASK_FAILED,
                "轻量编辑补丁未应用", Map.of(
                        "taskId", taskId,
                        "status", applyResult == null ? "missing" : applyResult.status(),
                        "reason", applyResult == null ? "patch_result_missing" : applyResult.reason(),
                        "rejectedOperations", applyResult == null ? List.of() : applyResult.rejectedOperations(),
                        "rollbackStatus", restoreResult.status()
                ));
        return buildFailedResult(taskId, failedMessage);
    }

    /** 处理发布校验失败，只有通过校验的编辑才允许提交事务。 */
    private LightweightEditResult handlePublicationValidationFailure(
            GenerationTaskRequest request,
            App app,
            User loginUser,
            String taskId,
            List<PatchOperation> patchOperations,
            EditValidationPlan validationPlan,
            BackgroundValidationService.ValidationResult validationResult,
            EditWorkspaceTransaction workspaceTransaction,
            Path projectRoot) {
        EditFileSnapshotService.RestoreResult restoreResult = rollbackWorkspace(
                request, taskId, "发布验证未通过，已回滚本次编辑",
                workspaceTransaction, projectRoot);
        String validationMessage = validationResult == null
                ? "验证服务未返回结果"
                : StrUtil.blankToDefault(validationResult.message(), "验证未通过");
        String failedMessage = "发布验证未通过: " + validationMessage;
        editStatePersistenceService.recordEditResult(
                app.getId(), taskId, patchOperations, false);
        chatHistoryService.addChatMessage(
                app.getId(), failedMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
        generationEventPublisher.publishSafely(
                request,
                GenerationEventType.TASK_FAILED,
                "轻量编辑因验证失败未发布",
                Map.of(
                        "taskId", taskId,
                        "status", validationResult == null ? "failed" : validationResult.status(),
                        "message", validationMessage,
                        "validationLevel", validationPlan == null
                                ? "unknown"
                                : validationPlan.level().name(),
                        "rollbackStatus", restoreResult.status()
                )
        );
        return buildFailedResult(taskId, failedMessage);
    }

    /** 回滚编辑事务并同步恢复语义索引，随后发布统一回滚事件。 */
    private EditFileSnapshotService.RestoreResult rollbackWorkspace(
            GenerationTaskRequest request,
            String taskId,
            String message,
            EditWorkspaceTransaction workspaceTransaction,
            Path projectRoot) {
        EditFileSnapshotService.RestoreResult restoreResult = workspaceTransaction.rollback();
        if (!restoreResult.restoredFiles().isEmpty()) {
            patchExecutor.refreshIndex(projectRoot, restoreResult.restoredFiles());
        }
        generationEventPublisher.publishSafely(
                request,
                GenerationEventType.EDIT_ROLLBACK,
                message,
                Map.of(
                        "taskId", taskId,
                        "status", restoreResult.status(),
                        "restoredFiles", restoreResult.restoredFiles(),
                        "failedFiles", restoreResult.failedFiles()
                )
        );
        return restoreResult;
    }
    /** 完成成功并持久化终态。 */
    private LightweightEditResult completeSuccess(GenerationTaskRequest request,
                                                  App app,
                                                  User loginUser,
                                                  String taskId,
                                                  EditResult editResult,
                                                  PatchApplyResult applyResult,
                                                  EditValidationPlan validationPlan,
                                                  GenerationValidationObservation validationObservation) {
        String summaryMessage = buildSummaryMessage(editResult, applyResult, validationPlan);
        chatHistoryService.addChatMessage(
                app.getId(), summaryMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
        return new LightweightEditResult(
                taskId,
                GenerationEditRouteResult.ROUTE_LIGHTWEIGHT_EDIT,
                StrUtil.blankToDefault(editResult.summary(), "轻量编辑完成"),
                applyResult.appliedFiles(),
                applyResult.status(),
                ObservedValidationCompletionEvidenceFactory.forCompletedMutation(
                        applyResult.appliedOperationCount(), validationObservation)
        );
    }

    private void publishRouteEvent(GenerationTaskRequest request,
                                   String taskId,
                                   GenerationEditRouteResult routeResult) {
        generationEventPublisher.publishSafely(request, GenerationEventType.EDIT_ROUTE,
                "轻量编辑路由判断", Map.of(
                        "taskId", taskId,
                        "route", StrUtil.blankToDefault(routeResult.route(), "lightweight_edit"),
                        "reason", StrUtil.blankToDefault(routeResult.reason(), ""),
                        "confidence", routeResult.confidence()
                ));
    }

    private void publishLocatorEvent(GenerationTaskRequest request,
                                     String taskId,
                                     List<EditFileCandidate> candidates) {
        generationEventPublisher.publishSafely(request, GenerationEventType.FILE_LOCATOR,
                "文件定位完成", Map.of(
                        "taskId", taskId,
                        "candidateCount", candidates.size(),
                        "candidates", candidates.stream().map(EditFileCandidate::relativePath).toList()
                ));
    }

    private void publishPatchResult(GenerationTaskRequest request,
                                    String taskId,
                                    PatchApplyResult applyResult) {
        generationEventPublisher.publishSafely(request, GenerationEventType.PATCH_APPLY,
                "补丁应用完成", Map.of(
                        "taskId", taskId,
                        "status", applyResult == null ? "missing" : applyResult.status(),
                        "appliedCount", applyResult == null ? 0 : applyResult.appliedOperationCount(),
                        "reason", applyResult == null ? "patch_result_missing" : applyResult.reason(),
                        "rejectedOperations", applyResult == null ? List.of() : applyResult.rejectedOperations()
                ));
    }

    /** 构建并返回汇总消息。 */
    private String buildSummaryMessage(EditResult editResult,
                                       PatchApplyResult applyResult,
                                       EditValidationPlan validationPlan) {
        StringBuilder builder = new StringBuilder(StrUtil.blankToDefault(editResult.summary(), "轻量编辑完成"));
        if ("applied".equals(applyResult.status())) {
            builder.append("\n\n已成功修改 ").append(applyResult.appliedOperationCount()).append(" 个文件。");
        } else if ("rejected".equals(applyResult.status())) {
            builder.append("\n\n补丁应用被拒绝：").append(applyResult.reason());
        }
        if (validationPlan != null && validationPlan.requiresBackgroundValidation()) {
            builder.append("\n\n后台验证级别：").append(validationPlan.level().name());
            builder.append("（").append(validationPlan.reason()).append("）");
        }
        return builder.toString();
    }

    private void requireTaskId(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("taskId format is invalid");
        }
    }

    private void requireWorkspace(App app,
                                  CodeGenTypeEnum codeGenType,
                                  GenerationWorkspace workspace) {
        if (app == null || app.getId() == null || codeGenType == null || workspace == null
                || !app.getId().equals(workspace.appId()) || workspace.codeGenType() != codeGenType) {
            throw new IllegalArgumentException("lightweight edit workspace identity mismatch");
        }
    }

    private String generateTaskId() {
        return "edit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private LightweightEditResult buildFailedResult(String taskId, String reason) {
        return new LightweightEditResult(
                taskId,
                GenerationEditRouteResult.ROUTE_LIGHTWEIGHT_EDIT,
                "轻量编辑失败: " + StrUtil.blankToDefault(reason, "请稍后重试"),
                List.of(),
                "failed"
        );
    }
}
