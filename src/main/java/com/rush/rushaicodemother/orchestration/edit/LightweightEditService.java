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
import com.rush.rushaicodemother.service.GenerationTraceService;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * 受保护的文件名前缀（不允许轻量编辑修改）
     */
    private static final java.util.Set<String> PROTECTED_FILE_NAMES = java.util.Set.of(
            "package.json", "vite.config", "go.mod", "Dockerfile", "tsconfig"
    );

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
            String projectContext = buildProjectContextString(contextPackage);
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
            PatchApplyResult applyResult = generationPatchApplyService.applyWithoutChangePlan(
                    app.getId(), taskId, projectRoot, patchOperations, "lightweight_edit"
            );
            if (shouldRetryPatchApply(applyResult)) {
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
                    if (!retryPatchOperations.isEmpty()) {
                        PatchApplyResult retryApplyResult = generationPatchApplyService.applyWithoutChangePlan(
                                app.getId(), taskId, projectRoot, retryPatchOperations, "lightweight_edit_retry"
                        );
                        editResult = retryEditResult;
                        patchOperations = retryPatchOperations;
                        applyResult = retryApplyResult;
                    }
                } catch (Exception e) {
                    log.warn("轻量编辑补丁重试失败，保留首次补丁结果，appId: {}, taskId: {}", app.getId(), taskId, e);
                }
            }

            // 发送补丁应用事件
            generationEventPublisher.publish(request, GenerationEventType.PATCH_APPLY, "补丁应用完成", Map.of(
                    "taskId", taskId,
                    "status", applyResult.status(),
                    "appliedCount", applyResult.appliedOperationCount(),
                    "reason", StrUtil.blankToDefault(applyResult.reason(), "")
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
                    patchOperations, codeGenType, editResult.validation()
            );
            log.debug("验证计划: level={}, reason={}", validationPlan.level(), validationPlan.reason());

            // 9. 记录编辑状态
            boolean editSuccess = "applied".equals(applyResult.status());
            editStatePersistenceService.recordEditResult(
                    app.getId(), taskId, userMessage, patchOperations, editSuccess,
                    editSuccess ? "" : applyResult.reason()
            );

            // 10. 启动后台验证（异步，不阻塞用户）
            if (validationPlan.requiresBackgroundValidation() && editSuccess) {
                backgroundValidationService.executeBackgroundValidation(
                        taskId, app.getId(), loginUser.getId(), workspace,
                        patchOperations, validationPlan, userMessage
                );
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
                """.formatted(
                StrUtil.blankToDefault(userMessage, ""),
                StrUtil.blankToDefault(applyResult.reason(), ""),
                applyResult.rejectedOperations()
        );
        return aiCodeEditService.editCode(retryMessage, projectContext);
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
    private String buildProjectContextString(EditContextPackage contextPackage) {
        StringBuilder builder = new StringBuilder();
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
