package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;

import java.util.List;

/** 应用编辑的结果，包括一次可选的验证拒绝重试。 */
public record LightweightEditAttempt(
        EditResult editResult,
        List<PatchOperation> patchOperations,
        PatchApplyResult applyResult
) {
    public LightweightEditAttempt {
        patchOperations = patchOperations == null ? List.of() : List.copyOf(patchOperations);
    }
}
