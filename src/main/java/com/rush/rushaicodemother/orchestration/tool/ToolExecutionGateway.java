package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * Single gateway for AI tool side effects.
 */
@Service
@RequiredArgsConstructor
public class ToolExecutionGateway {

    private final GenerationPatchApplyService generationPatchApplyService;
    private final GenerationToolExecutionContextService toolExecutionContextService;

    public PatchApplyResult applyPatch(Long appId,
                                       Path projectRoot,
                                       List<PatchOperation> operations,
                                       String fallbackTaskId,
                                       String reason) {
        GenerationToolExecutionContext context = toolExecutionContextService.getContext(appId).orElse(null);
        if (context == null) {
            return PatchApplyResult.skipped(appId, fallbackTaskId, projectRoot.toString(), "change_plan_missing");
        }
        if (context.allowsBootstrapWrite()) {
            return generationPatchApplyService.applyWithoutChangePlan(
                    appId, context.taskId(), projectRoot, operations, context.reason()
            );
        }
        ChangePlan changePlan = context.changePlan();
        return generationPatchApplyService.apply(appId, context.taskId(), projectRoot, changePlan, operations);
    }

    public PatchApplyResult applyPatch(Long appId,
                                       Path projectRoot,
                                       PatchOperation operation,
                                       String fallbackTaskId,
                                       String reason) {
        return applyPatch(appId, projectRoot, List.of(operation), fallbackTaskId, reason);
    }
}
