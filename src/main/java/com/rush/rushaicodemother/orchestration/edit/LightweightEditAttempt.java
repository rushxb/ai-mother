package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;

import java.util.List;

/** Result of applying an edit, including one optional validation-rejection retry. */
public record LightweightEditAttempt(
        EditResult editResult,
        List<PatchOperation> patchOperations,
        PatchApplyResult applyResult
) {
    public LightweightEditAttempt {
        patchOperations = patchOperations == null ? List.of() : List.copyOf(patchOperations);
    }
}
