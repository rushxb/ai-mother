package com.rush.rushaicodemother.orchestration.patch;

import java.nio.file.Path;

/** 在真实的非符号项目根下解析的补丁目标。 */
public record PatchWorkspaceTarget(
        Path realRoot,
        String relativePath,
        Path absolutePath
) {
}
