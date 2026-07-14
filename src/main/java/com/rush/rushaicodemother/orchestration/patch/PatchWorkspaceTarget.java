package com.rush.rushaicodemother.orchestration.patch;

import java.nio.file.Path;

/** A patch target resolved beneath a real, non-symbolic project root. */
public record PatchWorkspaceTarget(
        Path realRoot,
        String relativePath,
        Path absolutePath
) {
}
