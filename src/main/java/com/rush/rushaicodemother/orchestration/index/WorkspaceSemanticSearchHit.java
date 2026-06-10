package com.rush.rushaicodemother.orchestration.index;

import java.util.List;

/**
 * 语义索引命中结果。
 */
public record WorkspaceSemanticSearchHit(
        String relativePath,
        String fileName,
        String matchType,
        int score,
        String preview,
        String recallSource,
        List<String> matchedTerms,
        List<String> matchedSymbols
) {
}
