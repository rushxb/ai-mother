package com.rush.rushaicodemother.orchestration.patch;

import java.util.List;

/** Result of validating a complete patch batch before any mutation occurs. */
public record PatchValidationResult(
        List<ValidatedPatchOperation> validOperations,
        List<String> rejectedOperations
) {
    public PatchValidationResult {
        validOperations = validOperations == null ? List.of() : List.copyOf(validOperations);
        rejectedOperations = rejectedOperations == null ? List.of() : List.copyOf(rejectedOperations);
    }
}
