package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 应用轻量级编辑补丁并拥有单一拒绝驱动模型重试。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LightweightEditPatchExecutor {

    private final GenerationPatchApplyService generationPatchApplyService;
    private final LightweightEditAiService lightweightEditAiService;
    private final LightweightEditOperationConverter operationConverter;
    private final GenerationEventPublisher generationEventPublisher;
    private final WorkspaceSemanticIndexService workspaceSemanticIndexService;

    /**
 * 在有界重试策略下应用补丁操作。
 *
 * @param request 请求参数
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param projectRoot 项目根
 * @param userMessage 用户消息
 * @param projectContext 项目上下文
 * @param editResult 编辑结果
 * @param patchOperations 补丁操作
 * @param workspaceTransaction 编辑工作区事务
 * @param managedModelCalls 受生命周期管理的模型调用集合
 * @return 方法执行结果
 */
    public LightweightEditAttempt applyWithRetry(GenerationTaskRequest request,
                                                 Long appId,
                                                 String taskId,
                                                 Path projectRoot,
                                                 String userMessage,
                                                 String projectContext,
                                                 EditResult editResult,
                                                 List<PatchOperation> patchOperations,
                                                 EditWorkspaceTransaction workspaceTransaction,
                                                 boolean managedModelCalls) {
        PatchApplyResult applyResult = applyOnce(
                appId, taskId, projectRoot, patchOperations, "lightweight_edit");
        if (!shouldRetry(applyResult)) {
            return new LightweightEditAttempt(editResult, patchOperations, applyResult);
        }

        generationEventPublisher.publishSafely(request, GenerationEventType.PATCH_APPLY,
                "补丁应用被拒绝，正在重新生成补丁", Map.of(
                        "taskId", taskId,
                        "status", applyResult.status(),
                        "reason", applyResult.reason(),
                        "rejectedOperations", applyResult.rejectedOperations()
                ));
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            EditResult retryEditResult = managedModelCalls
                    ? lightweightEditAiService.retryAfterPatchRejection(
                    taskId, userMessage, projectContext, applyResult, diagnostic(applyResult))
                    : lightweightEditAiService.retryAfterPatchRejection(
                    userMessage, projectContext, applyResult, diagnostic(applyResult));
            List<PatchOperation> retryOperations = retryEditResult == null
                    ? List.of()
                    : operationConverter.convert(retryEditResult.operations());
            if (retryOperations.isEmpty()) {
                return new LightweightEditAttempt(editResult, patchOperations, applyResult);
            }
            workspaceTransaction.include(retryOperations);
            PatchApplyResult retryApplyResult = applyOnce(
                    appId, taskId, projectRoot, retryOperations, "lightweight_edit_retry");
            return new LightweightEditAttempt(retryEditResult, retryOperations, retryApplyResult);
        } catch (GenerationExecutionPolicyException executionPolicyFailure) {
            throw executionPolicyFailure;
        } catch (Exception exception) {
            log.warn("Lightweight patch retry failed; preserve the initial result, appId: {}, taskId: {}",
                    appId, taskId, LogExceptionSanitizer.sanitize(exception));
            return new LightweightEditAttempt(editResult, patchOperations, applyResult);
        }
    }

    public PatchApplyResult applyOnce(Long appId,
                                      String taskId,
                                      Path projectRoot,
                                      List<PatchOperation> patchOperations,
                                      String executionContext) {
        return generationPatchApplyService.applyWithoutChangePlan(
                appId, taskId, projectRoot, patchOperations, executionContext);
    }

    /**
 * 刷新索引{@code If}{@code Applied}。
 *
 * @param projectRoot 项目根
 * @param patchOperations 补丁操作
 * @param applyResult {@code applyResult} 对应的调用参数
 */
    public void refreshIndexIfApplied(Path projectRoot,
                                      List<PatchOperation> patchOperations,
                                      PatchApplyResult applyResult) {
        if (applyResult == null || !"applied".equals(applyResult.status())) {
            return;
        }
        refreshIndex(projectRoot, changedFiles(patchOperations));
    }

    /**
 * 刷新索引。
 *
 * @param projectRoot 项目根
 * @param relativePaths 待处理的 {@code relativePaths} 集合
 */
    public void refreshIndex(Path projectRoot, List<String> relativePaths) {
        if (projectRoot == null || relativePaths == null || relativePaths.isEmpty()) {
            return;
        }
        workspaceSemanticIndexService.refreshFilesIndex(projectRoot, relativePaths);
    }

    /**
 * 返回变更文件。
 *
 * @param patchOperations 补丁操作
 * @return 轻量编辑补丁集合
 */
    public List<String> changedFiles(List<PatchOperation> patchOperations) {
        if (patchOperations == null || patchOperations.isEmpty()) {
            return List.of();
        }
        return patchOperations.stream()
                .map(PatchOperation::relativePath)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
    }

    /**
 * 返回{@code diagnostic}。
 *
 * @param applyResult {@code applyResult} 对应的调用参数
 * @return 处理后的轻量编辑补丁文本
 */
    public String diagnostic(PatchApplyResult applyResult) {
        if (applyResult == null) {
            return "补丁结果不可用";
        }
        String reason = StrUtil.blankToDefault(applyResult.reason(), applyResult.status());
        if (applyResult.rejectedOperations().isEmpty()) {
            return reason;
        }
        return reason + "，拒绝操作: " + applyResult.rejectedOperations();
    }

    /** 判断是否应执行重试。 */
    private boolean shouldRetry(PatchApplyResult applyResult) {
        if (applyResult == null || !"rejected".equals(applyResult.status())) {
            return false;
        }
        if (!"patch_operation_validation_failed".equals(applyResult.reason())) {
            return false;
        }
        return applyResult.rejectedOperations().stream()
                .noneMatch(operation -> operation.contains("path_outside_project"));
    }
}
