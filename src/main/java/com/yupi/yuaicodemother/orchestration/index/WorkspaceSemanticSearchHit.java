package com.yupi.yuaicodemother.orchestration.index;

/**
 * 语义索引命中结果。
 */
public record WorkspaceSemanticSearchHit(
        String relativePath,
        String fileName,
        String matchType,
        int score,
        String preview
) {
}
