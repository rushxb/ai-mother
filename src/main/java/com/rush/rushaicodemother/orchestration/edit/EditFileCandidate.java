package com.rush.rushaicodemother.orchestration.edit;

import java.util.List;

/**
 * 文件定位候选结果。
 */
public record EditFileCandidate(
        String relativePath,
        String fileName,
        String matchType,
        int score,
        String reason,
        List<String> matchedTerms
) {
}
