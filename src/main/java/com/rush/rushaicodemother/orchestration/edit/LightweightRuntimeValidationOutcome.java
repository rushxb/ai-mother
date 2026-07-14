package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;

import java.util.List;

/** Final synchronous validation state for a runtime-error repair edit. */
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
