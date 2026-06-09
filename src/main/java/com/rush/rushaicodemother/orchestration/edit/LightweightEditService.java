package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.AiCodeEditService;
import com.rush.rushaicodemother.ai.AiCodeEditServiceFactory;
import com.rush.rushaicodemother.ai.model.EditOperation;
import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.DevServerManager;
import com.rush.rushaicodemother.service.GenerationTraceService;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 轻量编辑执行服务。
 * 负责协调路由判断、文件定位、AI 编辑和补丁应用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LightweightEditService {

    private final GenerationEditRouteService generationEditRouteService;
    private final EditFileLocatorService editFileLocatorService;
    private final AiCodeEditServiceFactory aiCodeEditServiceFactory;
    private final GenerationPatchApplyService generationPatchApplyService;
    private final GenerationEventPublisher generationEventPublisher;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationAppStateService generationAppStateService;
    private final GenerationTraceService generationTraceService;
    private final ChatHistoryService chatHistoryService;
    private final UserCreditService userCreditService;
    private final EditValidationPolicyService editValidationPolicyService;
    private final BackgroundValidationService backgroundValidationService;
    private final EditStatePersistenceService editStatePersistenceService;
    private final WorkspaceSemanticIndexService workspaceSemanticIndexService;
    private final DevServerManager devServerManager;
    private final EditFileSnapshotService editFileSnapshotService;

    /**
     * 受保护的文件名前缀（不允许轻量编辑修改）
     */
    private static final java.util.Set<String> PROTECTED_FILE_NAMES = java.util.Set.of(
            "package.json", "vite.config", "go.mod", "Dockerfile", "tsconfig"
    );
    private static final int MAX_RUNTIME_REPAIR_ROUNDS = 3;

    /**
     * 执行轻量编辑。
     * 如果路由判断为重型生成，返回 null，由调用方走重型生成路径。
     *
     * @param request 生成任务请求
     * @return 轻量编辑结果，如果走重型生成则返回 null
     */
    public LightweightEditResult execute(GenerationTaskRequest request) {
        if (request == null || request.app() == null || request.loginUser() == null) {
            return null;
        }

        App app = request.app();
        User loginUser = request.loginUser();
        String userMessage = request.message();

        // 1. 路由判断
        GenerationEditRouteResult routeResult = generationEditRouteService.route(app, userMessage);
        if (!routeResult.isLightweightEdit()) {
            log.info("路由判断走重型生成，appId: {}, reason: {}", app.getId(), routeResult.reason());
            return null;
        }

        log.info("路由判断走轻量编辑，appId: {}, reason: {}, confidence: {}", app.getId(), routeResult.reason(), routeResult.confidence());

        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenType == null) {
            return null;
        }

        GenerationWorkspace workspace = generationWorkspaceService.resolve(app, codeGenType);
        if (!workspace.exists()) {
            return null;
        }

        String taskId = generateTaskId();

        try {
            // 发送路由事件
            generationEventPublisher.publish(request, GenerationEventType.EDIT_ROUTE, "轻量编辑路由判断", Map.of(
                    "taskId", taskId,
                    "route", routeResult.route(),
                    "reason", routeResult.reason(),
                    "confidence", routeResult.confidence()
            ));

            // 记录用户消息到聊天历史
            chatHistoryService.addChatMessage(app.getId(), userMessage, ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());

            // 开始 trace
            generationTraceService.startTask(
                    taskId, app.getId(), loginUser.getId(),
                    codeGenType, codeGenType,
                    userMessage, userMessage,
                    routeResult.requiresBuild(), "lightweight",
                    "lightweight_edit"
            );

            // 标记生成开始
            generationAppStateService.markGenerationStarted(app.getId(), AppConstant.GENERATING_STAGE_UPDATE);

            // 2. 文件定位
            List<EditFileCandidate> candidates = editFileLocatorService.locate(workspace, userMessage, codeGenType);
            if (candidates.isEmpty()) {
                log.warn("文件定位未找到候选文件，appId: {}", app.getId());
                 generationEventPublisher.publish(request, GenerationEventType.TASK_FAILED, "文件定位未找到候选文件", Map.of(
                        "taskId", taskId,
                        "reason", "no_candidates_found"
                ));
                generationAppStateService.markGenerationFinished(app.getId());
                return buildFailedResult(taskId, "文件定位未找到候选文件");
            }

            // 发送文件定位事件
            generationEventPublisher.publish(request, GenerationEventType.FILE_LOCATOR, "文件定位完成", Map.of(
                    "taskId", taskId,
                    "candidateCount", candidates.size(),
                    "candidates", candidates.stream().map(EditFileCandidate::relativePath).toList()
            ));

            // 3. 构建上下文
            EditContextPackage contextPackage = editFileLocatorService.buildContextPackage(workspace, candidates);
            if (contextPackage.isEmpty()) {
                log.warn("上下文构建为空，appId: {}", app.getId());
                generationAppStateService.markGenerationFinished(app.getId());
                return buildFailedResult(taskId, "上下文构建为空");
            }

            // 4. AI 编辑
            String projectContext = buildProjectContextString(contextPackage, app.getId(), userMessage);
            AiCodeEditService aiCodeEditService = aiCodeEditServiceFactory.createAiCodeEditService();
            EditResult editResult;
            try {
                editResult = aiCodeEditService.editCode(userMessage, projectContext);
            } catch (Exception e) {
                log.error("AI 编辑服务调用失败，appId: {}", app.getId(), e);
                String userFriendlyMessage = extractUserFriendlyMessage(e);
                generationAppStateService.markGenerationFinished(app.getId());
                return buildFailedResult(taskId, userFriendlyMessage);
            }

            if (editResult == null || editResult.operations() == null || editResult.operations().isEmpty()) {
                log.warn("AI 编辑返回空操作，appId: {}", app.getId());
                generationAppStateService.markGenerationFinished(app.getId());
                return buildFailedResult(taskId, "AI 编辑返回空操作");
            }

            // 5. 转换为 PatchOperation
            List<PatchOperation> patchOperations = convertToPatchOperations(editResult.operations());
            if (patchOperations.isEmpty()) {
                generationAppStateService.markGenerationFinished(app.getId());
                return buildFailedResult(taskId, "无有效补丁操作");
            }

            // 6. 应用补丁
            Path projectRoot = workspace.canonicalRootPath();
            boolean runtimeErrorRepair = editValidationPolicyService.isRuntimeErrorRepairRequest(userMessage);
            EditFileSnapshotService.EditFileSnapshot editSnapshot = runtimeErrorRepair
                    ? editFileSnapshotService.capture(projectRoot, patchOperations)
                    : null;
            EditAttempt editAttempt = applyEditWithPatchRetry(
                    request, app, taskId, projectRoot, userMessage, projectContext,
                    editResult, patchOperations, runtimeErrorRepair, editSnapshot
            );
            editResult = editAttempt.editResult();
            patchOperations = editAttempt.patchOperations();
            PatchApplyResult applyResult = editAttempt.applyResult();

            // 发送补丁应用事件
            generationEventPublisher.publish(request, GenerationEventType.PATCH_APPLY, "补丁应用完成", Map.of(
                    "taskId", taskId,
                    "status", applyResult.status(),
                    "appliedCount", applyResult.appliedOperationCount(),
                    "reason", StrUtil.blankToDefault(applyResult.reason(), ""),
                    "rejectedOperations", applyResult.rejectedOperations()
            ));

            // 7. 增量更新索引
            if ("applied".equals(applyResult.status())) {
                List<String> changedFiles = patchOperations.stream()
                        .map(PatchOperation::relativePath)
                        .filter(StrUtil::isNotBlank)
                        .toList();
                workspaceSemanticIndexService.refreshFilesIndex(projectRoot, changedFiles);
                log.debug("增量更新索引完成，文件数: {}", changedFiles.size());
            }

            // 8. 确定验证计划
            EditValidationPlan validationPlan = editValidationPolicyService.determineValidationPlan(
                    patchOperations, codeGenType, editResult.validation(), userMessage
            );
            log.debug("验证计划: level={}, reason={}", validationPlan.level(), validationPlan.reason());

            // 9. 记录编辑状态
            boolean editSuccess = "applied".equals(applyResult.status());
            if (!runtimeErrorRepair) {
                editStatePersistenceService.recordEditResult(
                        app.getId(), taskId, userMessage, patchOperations, editSuccess,
                        editSuccess ? "" : applyResult.reason()
                );
            }

            // 10. 启动后台验证。运行时报错修复必须同步验证，避免失败补丁污染预览项目。
            if (validationPlan.requiresBackgroundValidation() && editSuccess && runtimeErrorRepair) {
                RuntimeValidationOutcome validationOutcome = validateRuntimeEditWithRetries(
                        request, app, loginUser, taskId, workspace, projectRoot, userMessage,
                        projectContext, editResult, patchOperations, applyResult, validationPlan, editSnapshot
                );
                editResult = validationOutcome.editResult();
                patchOperations = validationOutcome.patchOperations();
                applyResult = validationOutcome.applyResult();
                validationPlan = validationOutcome.validationPlan();
                if (!validationOutcome.success()) {
                    return handleRuntimeValidationFailure(
                            request, app, loginUser, taskId, userMessage, patchOperations,
                            validationPlan, validationOutcome.validationResult(), editSnapshot, projectRoot
                    );
                }
                editStatePersistenceService.recordEditResult(
                        app.getId(), taskId, userMessage, patchOperations, true, ""
                );
            } else if (validationPlan.requiresBackgroundValidation() && editSuccess) {
                backgroundValidationService.executeBackgroundValidation(
                        taskId, app.getId(), loginUser.getId(), workspace,
                        patchOperations, validationPlan, userMessage
                );
            }

            if (!editSuccess) {
                String failedMessage = "补丁未应用: " + buildPatchApplyDiagnostic(applyResult);
                if (runtimeErrorRepair) {
                    editStatePersistenceService.recordEditResult(
                            app.getId(), taskId, userMessage, patchOperations, false, failedMessage
                    );
                }
                chatHistoryService.addChatMessage(app.getId(), failedMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                generationEventPublisher.publish(request, GenerationEventType.TASK_FAILED, "轻量编辑补丁未应用", Map.of(
                        "taskId", taskId,
                        "status", applyResult.status(),
                        "reason", StrUtil.blankToDefault(applyResult.reason(), ""),
                        "rejectedOperations", applyResult.rejectedOperations()
                ));
                generationAppStateService.markGenerationFinished(app.getId());
                generationTraceService.completeTask(taskId, "failed", null, failedMessage);
                return buildFailedResult(taskId, failedMessage);
            }

            // 11. 记录聊天历史（AI 回复）
            String summaryMessage = buildSummaryMessage(editResult, applyResult, validationPlan);
            chatHistoryService.addChatMessage(app.getId(), summaryMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());

            // 发送完成事件
            generationEventPublisher.publish(request, GenerationEventType.TASK_DONE, "轻量编辑完成", Map.of(
                    "taskId", taskId,
                    "route", GenerationEditRouteResult.ROUTE_LIGHTWEIGHT_EDIT,
                    "status", applyResult.status(),
                    "summary", editResult.summary()
            ));

            // 标记生成完成
            generationAppStateService.markGenerationFinished(app.getId());

            // 完成 trace
            generationTraceService.completeTask(taskId, "success", null, null);

            // 扣减用户积分（轻量编辑成功后）
            userCreditService.chargeGenerationTask(taskId);

            return new LightweightEditResult(
                    taskId,
                    GenerationEditRouteResult.ROUTE_LIGHTWEIGHT_EDIT,
                    editResult.summary(),
                    applyResult.appliedFiles(),
                    applyResult.status()
            );

        } catch (Exception e) {
            log.error("轻量编辑执行失败，appId: {}", app.getId(), e);
            generationEventPublisher.publish(request, GenerationEventType.TASK_FAILED, "轻量编辑执行失败", Map.of(
                    "taskId", taskId,
                    "error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName())
            ));
            generationAppStateService.markGenerationFinished(app.getId());
            generationTraceService.completeTask(taskId, "failed", null, null);
            return buildFailedResult(taskId, e.getMessage());
        }
    }

    private EditAttempt applyEditWithPatchRetry(GenerationTaskRequest request,
                                                App app,
                                                String taskId,
                                                Path projectRoot,
                                                String userMessage,
                                                String projectContext,
                                                EditResult editResult,
                                                List<PatchOperation> patchOperations,
                                                boolean runtimeErrorRepair,
                                                EditFileSnapshotService.EditFileSnapshot editSnapshot) {
        PatchApplyResult applyResult = generationPatchApplyService.applyWithoutChangePlan(
                app.getId(), taskId, projectRoot, patchOperations, "lightweight_edit"
        );
        if (!shouldRetryPatchApply(applyResult)) {
            return new EditAttempt(editResult, patchOperations, applyResult);
        }

        generationEventPublisher.publish(request, GenerationEventType.PATCH_APPLY, "补丁应用被拒绝，正在重新生成补丁", Map.of(
                "taskId", taskId,
                "status", applyResult.status(),
                "reason", StrUtil.blankToDefault(applyResult.reason(), ""),
                "rejectedOperations", applyResult.rejectedOperations()
        ));
        try {
            EditResult retryEditResult = retryEditAfterPatchRejection(userMessage, projectContext, applyResult);
            List<PatchOperation> retryPatchOperations = retryEditResult == null
                    ? List.of()
                    : convertToPatchOperations(retryEditResult.operations());
            if (retryPatchOperations.isEmpty()) {
                return new EditAttempt(editResult, patchOperations, applyResult);
            }
            if (runtimeErrorRepair) {
                editFileSnapshotService.captureMissing(editSnapshot, retryPatchOperations);
            }
            PatchApplyResult retryApplyResult = generationPatchApplyService.applyWithoutChangePlan(
                    app.getId(), taskId, projectRoot, retryPatchOperations, "lightweight_edit_retry"
            );
            return new EditAttempt(retryEditResult, retryPatchOperations, retryApplyResult);
        } catch (Exception e) {
            log.warn("轻量编辑补丁重试失败，保留首次补丁结果，appId: {}, taskId: {}", app.getId(), taskId, e);
            return new EditAttempt(editResult, patchOperations, applyResult);
        }
    }

    private RuntimeValidationOutcome validateRuntimeEditWithRetries(GenerationTaskRequest request,
                                                                    App app,
                                                                    User loginUser,
                                                                    String taskId,
                                                                    GenerationWorkspace workspace,
                                                                    Path projectRoot,
                                                                    String userMessage,
                                                                    String projectContext,
                                                                    EditResult editResult,
                                                                    List<PatchOperation> patchOperations,
                                                                    PatchApplyResult applyResult,
                                                                    EditValidationPlan validationPlan,
                                                                    EditFileSnapshotService.EditFileSnapshot editSnapshot) {
        BackgroundValidationService.ValidationResult validationResult = backgroundValidationService.executeValidation(
                taskId, app.getId(), loginUser.getId(), workspace,
                patchOperations, validationPlan, userMessage
        );
        int repairRound = 2;
        while (!validationResult.isSuccess() && repairRound <= MAX_RUNTIME_REPAIR_ROUNDS) {
            RuntimeRetryResult retryResult = retryRuntimeErrorRepair(
                    request,
                    app,
                    loginUser,
                    taskId,
                    workspace,
                    projectRoot,
                    userMessage,
                    projectContext,
                    validationPlan,
                    validationResult,
                    editSnapshot,
                    repairRound
            );
            if (retryResult.success()) {
                editResult = retryResult.editResult();
                patchOperations = retryResult.patchOperations();
                applyResult = retryResult.applyResult();
                validationPlan = retryResult.validationPlan();
                validationResult = retryResult.validationResult();
            } else {
                validationResult = retryResult.validationResult();
            }
            repairRound++;
        }
        return new RuntimeValidationOutcome(
                validationResult.isSuccess(),
                editResult,
                patchOperations,
                applyResult,
                validationPlan,
                validationResult
        );
    }

    private LightweightEditResult handleRuntimeValidationFailure(GenerationTaskRequest request,
                                                                 App app,
                                                                 User loginUser,
                                                                 String taskId,
                                                                 String userMessage,
                                                                 List<PatchOperation> patchOperations,
                                                                 EditValidationPlan validationPlan,
                                                                 BackgroundValidationService.ValidationResult validationResult,
                                                                 EditFileSnapshotService.EditFileSnapshot editSnapshot,
                                                                 Path projectRoot) {
        EditFileSnapshotService.RestoreResult restoreResult = restoreRuntimeEditSnapshot(
                request, app.getId(), taskId, editSnapshot, projectRoot
        );
        String failedMessage = "修复后验证未通过: " + validationResult.message();
        chatHistoryService.addChatMessage(app.getId(), failedMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
        editStatePersistenceService.recordEditResult(
                app.getId(), taskId, userMessage, patchOperations, false,
                validationResult.message()
        );
        generationEventPublisher.publish(request, GenerationEventType.TASK_FAILED, "轻量编辑验证未通过", Map.of(
                "taskId", taskId,
                "status", validationResult.status(),
                "message", StrUtil.blankToDefault(validationResult.message(), ""),
                "validationLevel", validationPlan.level().name(),
                "rollbackStatus", restoreResult.status()
        ));
        generationAppStateService.markGenerationFinished(app.getId());
        generationTraceService.completeTask(taskId, "failed", null, validationResult.message());
        return buildFailedResult(taskId, failedMessage);
    }

    /**
     * 将 AI 返回的 EditOperation 转换为 PatchOperation。
     */
    private List<PatchOperation> convertToPatchOperations(List<EditOperation> editOperations) {
        List<PatchOperation> patchOperations = new ArrayList<>();
        for (EditOperation editOp : editOperations) {
            if (editOp == null || StrUtil.isBlank(editOp.action()) || StrUtil.isBlank(editOp.relativePath())) {
                continue;
            }

            String relativePath = editOp.relativePath().replace('\\', '/');

            // 安全检查：不允许修改受保护的文件
            if (isProtectedFile(relativePath)) {
                log.warn("跳过受保护文件的修改: {}", relativePath);
                continue;
            }

            String action = editOp.action().toLowerCase();
            switch (action) {
                case "replace" -> {
                    if (StrUtil.isNotBlank(editOp.oldContent()) && editOp.newContent() != null) {
                        patchOperations.add(PatchOperation.replace(relativePath, editOp.oldContent(), editOp.newContent()));
                    }
                }
                case "modify" -> {
                    if (StrUtil.isNotBlank(editOp.content())) {
                        patchOperations.add(PatchOperation.modify(relativePath, editOp.content()));
                    }
                }
                case "add" -> {
                    if (StrUtil.isNotBlank(editOp.content())) {
                        patchOperations.add(PatchOperation.add(relativePath, editOp.content()));
                    }
                }
                default -> log.warn("不支持的编辑操作类型: {}", action);
            }
        }
        return patchOperations;
    }

    private boolean shouldRetryPatchApply(PatchApplyResult applyResult) {
        if (applyResult == null || !"rejected".equals(applyResult.status())) {
            return false;
        }
        if (!"patch_operation_validation_failed".equals(applyResult.reason())) {
            return false;
        }
        return applyResult.rejectedOperations().stream()
                .noneMatch(operation -> operation.contains("path_outside_project"));
    }

    private EditResult retryEditAfterPatchRejection(String userMessage,
                                                    String projectContext,
                                                    PatchApplyResult applyResult) {
        AiCodeEditService aiCodeEditService = aiCodeEditServiceFactory.createAiCodeEditService();
        String retryMessage = """
                %s

                上一次补丁被本地校验拒绝，请重新审视项目上下文后只返回可应用的 JSON 编辑操作。
                拒绝原因: %s
                拒绝操作: %s

                约束:
                1. replace.oldContent 必须逐字复制自项目上下文中的真实文件内容。
                2. 如果无法稳定精确替换局部片段，改用 modify 覆盖完整文件，但只能覆盖确实需要修改的文件。
                3. 不要猜测未提供内容的文件结构。
                4. 如果拒绝操作包含 undeclared_bare_import，禁止继续 import 该包，也不要修改 package.json；改用项目已声明依赖、已有组件、CSS、Unicode 字符或内联 SVG 实现。
                """.formatted(
                StrUtil.blankToDefault(userMessage, ""),
                StrUtil.blankToDefault(applyResult.reason(), ""),
                buildPatchApplyDiagnostic(applyResult)
        );
        return aiCodeEditService.editCode(retryMessage, projectContext);
    }

    private RuntimeRetryResult retryRuntimeErrorRepair(GenerationTaskRequest request,
                                                       App app,
                                                       User loginUser,
                                                       String taskId,
                                                       GenerationWorkspace workspace,
                                                       Path projectRoot,
                                                       String userMessage,
                                                       String projectContext,
                                                       EditValidationPlan previousValidationPlan,
                                                       BackgroundValidationService.ValidationResult previousValidationResult,
                                                       EditFileSnapshotService.EditFileSnapshot editSnapshot,
                                                       int round) {
        generationEventPublisher.publish(request, GenerationEventType.REPAIR_START, "修复后验证失败，开始自动二次修复", Map.of(
                "taskId", taskId,
                "round", round,
                "validationLevel", previousValidationPlan.level().name(),
                "message", StrUtil.blankToDefault(previousValidationResult.message(), "")
        ));
        try {
            String retryContext = rebuildRetryContext(workspace, userMessage, previousValidationResult, projectContext);
            EditResult retryEditResult = retryEditAfterValidationFailure(userMessage, retryContext, previousValidationResult);
            List<PatchOperation> retryPatchOperations = retryEditResult == null
                    ? List.of()
                    : convertToPatchOperations(retryEditResult.operations());
            if (retryPatchOperations.isEmpty()) {
                return RuntimeRetryResult.failed(previousValidationResult);
            }
            editFileSnapshotService.captureMissing(editSnapshot, retryPatchOperations);

            PatchApplyResult retryApplyResult = generationPatchApplyService.applyWithoutChangePlan(
                    app.getId(), taskId, projectRoot, retryPatchOperations, "lightweight_runtime_retry"
            );
            generationEventPublisher.publish(request, GenerationEventType.PATCH_APPLY, "自动二次修复补丁应用完成", Map.of(
                    "taskId", taskId,
                    "status", retryApplyResult.status(),
                    "appliedCount", retryApplyResult.appliedOperationCount(),
                    "reason", StrUtil.blankToDefault(retryApplyResult.reason(), ""),
                    "rejectedOperations", retryApplyResult.rejectedOperations()
            ));
            if (!"applied".equals(retryApplyResult.status())) {
                return RuntimeRetryResult.failed(BackgroundValidationService.ValidationResult.failed(
                        taskId, "自动二次修复补丁未应用: " + buildPatchApplyDiagnostic(retryApplyResult)
                ));
            }

            List<String> changedFiles = retryPatchOperations.stream()
                    .map(PatchOperation::relativePath)
                    .filter(StrUtil::isNotBlank)
                    .toList();
            workspaceSemanticIndexService.refreshFilesIndex(projectRoot, changedFiles);

            EditValidationPlan retryValidationPlan = editValidationPolicyService.determineValidationPlan(
                    retryPatchOperations, workspace.codeGenType(), retryEditResult.validation(), userMessage
            );
            BackgroundValidationService.ValidationResult retryValidationResult = backgroundValidationService.executeValidation(
                    taskId, app.getId(), loginUser.getId(), workspace,
                    retryPatchOperations, retryValidationPlan, userMessage
            );
            if (!retryValidationResult.isSuccess()) {
                return RuntimeRetryResult.failed(retryValidationResult);
            }
            return RuntimeRetryResult.success(retryEditResult, retryPatchOperations, retryApplyResult, retryValidationPlan, retryValidationResult);
        } catch (Exception e) {
            log.warn("自动二次修复失败，appId: {}, taskId: {}, error: {}", app.getId(), taskId, e.getMessage(), e);
            return RuntimeRetryResult.failed(BackgroundValidationService.ValidationResult.failed(
                    taskId, "自动二次修复异常: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName())
            ));
        }
    }

    private EditFileSnapshotService.RestoreResult restoreRuntimeEditSnapshot(GenerationTaskRequest request,
                                                                             Long appId,
                                                                             String taskId,
                                                                             EditFileSnapshotService.EditFileSnapshot editSnapshot,
                                                                             Path projectRoot) {
        EditFileSnapshotService.RestoreResult restoreResult = editFileSnapshotService.restore(editSnapshot);
        generationEventPublisher.publish(request, GenerationEventType.EDIT_ROLLBACK, "运行时修复验证失败，已回滚本次编辑", Map.of(
                "taskId", taskId,
                "status", restoreResult.status(),
                "restoredFiles", restoreResult.restoredFiles(),
                "failedFiles", restoreResult.failedFiles()
        ));
        if (restoreResult.restored()) {
            workspaceSemanticIndexService.refreshFilesIndex(projectRoot, restoreResult.restoredFiles());
            log.info("运行时修复失败后已回滚本次编辑，appId: {}, taskId: {}, files: {}",
                    appId, taskId, restoreResult.restoredFiles().size());
        } else {
            log.warn("运行时修复失败后回滚未完全成功，appId: {}, taskId: {}, status: {}, failedFiles: {}",
                    appId, taskId, restoreResult.status(), restoreResult.failedFiles());
        }
        return restoreResult;
    }

    private String rebuildRetryContext(GenerationWorkspace workspace,
                                       String userMessage,
                                       BackgroundValidationService.ValidationResult validationResult,
                                       String fallbackContext) {
        try {
            String validationMessage = validationResult == null ? "" : StrUtil.blankToDefault(validationResult.message(), "");
            List<EditFileCandidate> retryCandidates = editFileLocatorService.locate(
                    workspace,
                    userMessage + "\n\n修复后验证失败信息:\n" + validationMessage,
                    workspace.codeGenType()
            );
            EditContextPackage retryContextPackage = editFileLocatorService.buildContextPackage(workspace, retryCandidates);
            if (!retryContextPackage.isEmpty()) {
                return buildProjectContextString(retryContextPackage, workspace.appId(), userMessage);
            }
        } catch (Exception e) {
            log.debug("重建二次修复上下文失败: {}", e.getMessage());
        }
        return fallbackContext;
    }

    private EditResult retryEditAfterValidationFailure(String userMessage,
                                                       String projectContext,
                                                       BackgroundValidationService.ValidationResult validationResult) {
        AiCodeEditService aiCodeEditService = aiCodeEditServiceFactory.createAiCodeEditService();
        String retryMessage = """
                %s

                上一次修复补丁已应用，但修复后验证仍未通过。请基于下方验证失败信息做一次最小范围二次修复，只返回 JSON 编辑操作。

                验证失败信息:
                %s

                约束:
                1. 不要重复应用已经完成的修改。
                2. 优先修复验证日志中指向的文件、变量、import 或导出。
                3. 对 SyntaxError / already declared，必须检查同一作用域内 import、const、let、function、defineProps、解构声明是否重复。
                4. 如果无法确定，读取上下文中同名标识符出现最多的文件并做最小修改，不要整站重写。
                5. 不要新增项目 package.json 未声明的第三方依赖 import；如果需要图标或工具函数，优先复用现有依赖或用原生代码实现。
                """.formatted(
                StrUtil.blankToDefault(userMessage, ""),
                validationResult == null ? "" : StrUtil.blankToDefault(validationResult.message(), "")
        );
        return aiCodeEditService.editCode(retryMessage, projectContext);
    }

    private record RuntimeRetryResult(
            boolean success,
            EditResult editResult,
            List<PatchOperation> patchOperations,
            PatchApplyResult applyResult,
            EditValidationPlan validationPlan,
            BackgroundValidationService.ValidationResult validationResult
    ) {
        private static RuntimeRetryResult success(EditResult editResult,
                                                  List<PatchOperation> patchOperations,
                                                  PatchApplyResult applyResult,
                                                  EditValidationPlan validationPlan,
                                                  BackgroundValidationService.ValidationResult validationResult) {
            return new RuntimeRetryResult(true, editResult, patchOperations, applyResult, validationPlan, validationResult);
        }

        private static RuntimeRetryResult failed(BackgroundValidationService.ValidationResult validationResult) {
            return new RuntimeRetryResult(false, null, List.of(), null, null, validationResult);
        }
    }

    private record EditAttempt(
            EditResult editResult,
            List<PatchOperation> patchOperations,
            PatchApplyResult applyResult
    ) {
    }

    private record RuntimeValidationOutcome(
            boolean success,
            EditResult editResult,
            List<PatchOperation> patchOperations,
            PatchApplyResult applyResult,
            EditValidationPlan validationPlan,
            BackgroundValidationService.ValidationResult validationResult
    ) {
    }

    private String buildPatchApplyDiagnostic(PatchApplyResult applyResult) {
        if (applyResult == null) {
            return "补丁结果不可用";
        }
        String reason = StrUtil.blankToDefault(applyResult.reason(), applyResult.status());
        if (applyResult.rejectedOperations() == null || applyResult.rejectedOperations().isEmpty()) {
            return reason;
        }
        return reason + "，拒绝操作: " + applyResult.rejectedOperations();
    }

    /**
     * 检查文件是否受保护。
     */
    private boolean isProtectedFile(String relativePath) {
        String fileName = relativePath.contains("/") ? relativePath.substring(relativePath.lastIndexOf('/') + 1) : relativePath;
        return PROTECTED_FILE_NAMES.stream().anyMatch(fileName::startsWith);
    }

    /**
     * 构建项目上下文字符串。
     */
    private String buildProjectContextString(EditContextPackage contextPackage, Long appId, String userMessage) {
        StringBuilder builder = new StringBuilder();
        String recentDevServerOutput = buildRecentDevServerOutput(appId, userMessage);
        if (StrUtil.isNotBlank(recentDevServerOutput)) {
            builder.append(recentDevServerOutput).append("\n\n");
        }
        if (StrUtil.isNotBlank(contextPackage.projectIndex())) {
            builder.append(contextPackage.projectIndex()).append("\n\n");
        }
        if (contextPackage.candidates() != null && !contextPackage.candidates().isEmpty()) {
            builder.append("候选文件定位依据:\n");
            for (EditFileCandidate candidate : contextPackage.candidates()) {
                builder.append("- ")
                        .append(candidate.relativePath())
                        .append(" [")
                        .append(candidate.matchType())
                        .append(", score=")
                        .append(candidate.score())
                        .append("]: ")
                        .append(StrUtil.blankToDefault(candidate.reason(), ""));
                if (candidate.matchedTerms() != null && !candidate.matchedTerms().isEmpty()) {
                    builder.append("，命中: ").append(candidate.matchedTerms());
                }
                builder.append('\n');
            }
            builder.append('\n');
        }
        for (Map.Entry<String, String> entry : contextPackage.fileContents().entrySet()) {
            builder.append("文件: ").append(entry.getKey()).append("\n");
            builder.append("```\n").append(entry.getValue()).append("\n```\n\n");
        }
        return builder.toString();
    }

    private String buildRecentDevServerOutput(Long appId, String userMessage) {
        if (!editValidationPolicyService.isRuntimeErrorRepairRequest(userMessage)) {
            return "";
        }
        List<String> lines = devServerManager.getRecentOutputLines(appId, 60);
        if (lines.isEmpty()) {
            return "";
        }
        List<String> usefulLines = lines.stream()
                .filter(line -> {
                    String lower = line.toLowerCase();
                    return lower.contains("error")
                            || lower.contains("warn")
                            || lower.contains("syntaxerror")
                            || lower.contains("referenceerror")
                            || lower.contains("typeerror")
                            || lower.contains("failed to resolve")
                            || lower.contains("hmr update")
                            || lower.contains("[vite]");
                })
                .limit(30)
                .toList();
        if (usefulLines.isEmpty()) {
            return "";
        }
        return "最近 Dev Server 输出（用于复现和定位用户报错）:\n"
                + String.join("\n", usefulLines);
    }

    /**
     * 构建摘要消息。
     */
    private String buildSummaryMessage(EditResult editResult, PatchApplyResult applyResult, EditValidationPlan validationPlan) {
        StringBuilder builder = new StringBuilder();
        builder.append(editResult.summary());
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

    /**
     * 生成任务 ID。
     */
    private String generateTaskId() {
        return "edit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 从异常中提取用户友好的错误信息。
     */
    private String extractUserFriendlyMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return "AI 服务调用失败，请稍后重试";
        }
        // 余额不足
        if (message.contains("Insufficient Balance") || message.contains("402")) {
            return "AI 服务账户余额不足，请联系管理员充值";
        }
        // 请求过多（限流）
        if (message.contains("429") || message.contains("Too Many Requests")) {
            return "AI 服务请求过于频繁，请稍后重试";
        }
        // 认证失败
        if (message.contains("401") || message.contains("Unauthorized")) {
            return "AI 服务认证失败，请联系管理员检查配置";
        }
        // 服务不可用
        if (message.contains("503") || message.contains("Service Unavailable")) {
            return "AI 服务暂时不可用，请稍后重试";
        }
        // 其他错误
        return "AI 服务调用失败: " + message;
    }

    /**
     * 构建失败结果。
     */
    private LightweightEditResult buildFailedResult(String taskId, String reason) {
        return new LightweightEditResult(
                taskId,
                GenerationEditRouteResult.ROUTE_LIGHTWEIGHT_EDIT,
                "轻量编辑失败: " + reason,
                List.of(),
                "failed"
        );
    }
}
