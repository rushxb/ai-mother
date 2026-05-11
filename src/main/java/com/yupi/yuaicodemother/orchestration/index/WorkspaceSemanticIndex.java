package com.yupi.yuaicodemother.orchestration.index;

import java.util.List;

/**
 * 工作区语义索引快照。
 */
public record WorkspaceSemanticIndex(
        String schemaVersion,
        String rootPath,
        String workspaceSignature,
        long indexedAt,
        int indexedFileCount,
        List<WorkspaceSemanticIndexEntry> entries
) {
}
