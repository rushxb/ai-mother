package com.yupi.yuaicodemother.orchestration.index;

import java.util.List;

/**
 * 单个工作区文件的语义索引条目。
 */
public record WorkspaceSemanticIndexEntry(
        String relativePath,
        String fileName,
        String extension,
        long size,
        long lastModified,
        String searchableText,
        String contentExcerpt,
        List<String> terms
) {
}
