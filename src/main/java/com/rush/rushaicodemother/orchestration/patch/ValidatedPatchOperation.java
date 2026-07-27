package com.rush.rushaicodemother.orchestration.patch;

/** 对其操作、路径、目标状态和内容进行验证的修补操作。 */
public record ValidatedPatchOperation(
        String action,
        String relativePath,
        PatchWorkspaceTarget target,
        PatchOperation operation
) {
}
