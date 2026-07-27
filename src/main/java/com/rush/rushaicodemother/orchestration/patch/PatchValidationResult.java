package com.rush.rushaicodemother.orchestration.patch;

import java.util.List;

/** 在发生任何变更之前验证完整补丁批次的结果。 */
public record PatchValidationResult(
        List<ValidatedPatchOperation> validOperations,
        List<String> rejectedOperations
) {
    public PatchValidationResult {
        validOperations = validOperations == null ? List.of() : List.copyOf(validOperations);
        rejectedOperations = rejectedOperations == null ? List.of() : List.copyOf(rejectedOperations);
    }
}
