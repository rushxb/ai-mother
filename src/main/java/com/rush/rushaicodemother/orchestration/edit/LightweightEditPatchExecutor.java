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
    private final EditFileSnapshotService editFileSnapshotService;
    private final GenerationEventPublisher generationEventPublisher;
    private final WorkspaceSemanticIndexService workspaceSemanticIndexService;

    public LightweightEditAttempt applyWithRetry(GenerationTaskRequest request,
                                                 Long appId,
                                                 String taskId,
                                                 Path projectRoot,
                                                 String userMessage,
                                                 String projectContext,
                                                 EditResult editResult,
                                                 List<PatchOperation> patchOperations,
                                                 boolean runtimeErrorRepair,
                                                 EditFileSnapshotService.EditFileSnapshot editSnapshot,
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
            if (runtimeErrorRepair) {
                editFileSnapshotService.captureMissing(editSnapshot, retryOperations);
            }
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

    public void refreshIndexIfApplied(Path projectRoot,
                                      List<PatchOperation> patchOperations,
                                      PatchApplyResult applyResult) {
        if (applyResult == null || !"applied".equals(applyResult.status())) {
            return;
        }
        refreshIndex(projectRoot, changedFiles(patchOperations));
    }

    public void refreshIndex(Path projectRoot, List<String> relativePaths) {
        if (projectRoot == null || relativePaths == null || relativePaths.isEmpty()) {
            return;
        }
        workspaceSemanticIndexService.refreshFilesIndex(projectRoot, relativePaths);
    }

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
