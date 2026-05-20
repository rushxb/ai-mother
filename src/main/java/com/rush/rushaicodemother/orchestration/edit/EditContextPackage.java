package com.rush.rushaicodemother.orchestration.edit;

import java.util.List;
import java.util.Map;

/**
 * 轻量编辑上下文包，包含定位到的文件及其内容。
 */
public record EditContextPackage(
        List<EditFileCandidate> candidates,
        Map<String, String> fileContents,
        int totalChars,
        String projectIndex
) {

    public int fileCount() {
        return candidates == null ? 0 : candidates.size();
    }

    public boolean isEmpty() {
        return candidates == null || candidates.isEmpty();
    }
}
