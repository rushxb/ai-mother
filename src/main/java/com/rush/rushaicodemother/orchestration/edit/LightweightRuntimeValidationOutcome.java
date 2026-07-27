package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;

import java.util.List;

/** 运行时错误修复编辑的最终同步验证状态。 */
public record LightweightRuntimeValidationOutcome(
        boolean success,
        EditResult editResult,
        List<PatchOperation> patchOperations,
        PatchApplyResult applyResult,
        EditValidationPlan validationPlan,
        BackgroundValidationService.ValidationResult validationResult
) {
    public LightweightRuntimeValidationOutcome {
        patchOperations = patchOperations == null ? List.of() : List.copyOf(patchOperations);
    }
}
