package com.rush.rushaicodemother.orchestration.patch;

/** Patch operation whose action, path, target state, and content were validated. */
public record ValidatedPatchOperation(
        String action,
        String relativePath,
        PatchWorkspaceTarget target,
        PatchOperation operation
) {
}
