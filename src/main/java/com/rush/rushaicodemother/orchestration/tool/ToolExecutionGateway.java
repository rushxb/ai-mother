package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * AI 工具副作用的单一网关。
 */
@Service
@RequiredArgsConstructor
public class ToolExecutionGateway {

    private final GenerationPatchApplyService generationPatchApplyService;
    private final GenerationToolExecutionContextService toolExecutionContextService;
    private final GenerationExecutionContextService executionContextService;

    /**
 * 应用补丁。
 *
 * @param appId 应用编号
 * @param projectRoot 项目根
 * @param operations 操作
 * @param fallbackTaskId 回退任务编号
 * @param reason 原因
 * @return 补丁
 */
    public PatchApplyResult applyPatch(Long appId,
                                       Path projectRoot,
                                       List<PatchOperation> operations,
                                       String fallbackTaskId,
                                       String reason) {
        GenerationToolExecutionContext context = toolExecutionContextService.getContext(appId).orElse(null);
        if (context == null) {
            return PatchApplyResult.skipped(appId, fallbackTaskId, projectRoot.toString(), "change_plan_missing");
        }
        PatchApplyResult result;
        if (context.allowsBootstrapWrite()) {
            reserveToolWrites(context.taskId(), operationCount(operations));
            result = generationPatchApplyService.applyWithoutChangePlan(
                    appId, context.taskId(), projectRoot, operations, context.reason()
            );
        } else {
            ChangePlan changePlan = context.changePlan();
            reserveToolWrites(context.taskId(), operationCount(operations));
            result = generationPatchApplyService.apply(
                    appId, context.taskId(), projectRoot, changePlan, operations);
        }
        recordSuccessfulWorkspaceMutations(context.taskId(), result);
        return result;
    }

    /**
 * 应用补丁。
 *
 * @param appId 应用编号
 * @param projectRoot 项目根
 * @param operation 操作
 * @param fallbackTaskId 回退任务编号
 * @param reason 原因
 * @return 补丁
 */
    public PatchApplyResult applyPatch(Long appId,
                                       Path projectRoot,
                                       PatchOperation operation,
                                       String fallbackTaskId,
                                       String reason) {
        return applyPatch(appId, projectRoot, List.of(operation), fallbackTaskId, reason);
    }

    private int operationCount(List<PatchOperation> operations) {
        return operations == null ? 0 : operations.size();
    }

    private void reserveToolWrites(String taskId, int operationCount) {
        if (operationCount > 0) {
            executionContextService.consumeIfPresent(
                    taskId, GenerationBudgetKind.TOOL_WRITE, operationCount);
        }
    }

    private void recordSuccessfulWorkspaceMutations(String taskId, PatchApplyResult result) {
        if (result != null && "applied".equals(result.status()) && result.appliedOperationCount() > 0) {
            executionContextService.recordSuccessfulWorkspaceMutationsIfPresent(
                    taskId, result.appliedOperationCount());
        }
    }

}
