package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceCopyResult;

import java.time.Instant;

/** 磁盘 manifest 的紧凑 schema；绝对路径不属于可持久 provenance。 */
record SnapshotManifest(
        String schemaVersion,
        String snapshotId,
        String snapshotName,
        long appId,
        String codeGenType,
        String scope,
        String kind,
        String taskId,
        long executionEpoch,
        String copyPolicy,
        String treeHash,
        int fileCount,
        long byteCount,
        String createdAt
) {

    static final String CURRENT_SCHEMA_VERSION = "generation-snapshot/v1";
    static final String CURRENT_COPY_POLICY_VERSION = "workspace-copy/v1";

    static SnapshotManifest created(SnapshotCapture capture,
                                    WorkspaceCopyResult copyResult,
                                    String snapshotId,
                                    Instant createdAt) {
        return new SnapshotManifest(
                CURRENT_SCHEMA_VERSION,
                snapshotId,
                capture.snapshotName(),
                capture.scope().appId(),
                capture.scope().workspaceType().getValue(),
                capture.scope().relativePath(),
                capture.kind().value(),
                capture.creatorTaskId(),
                capture.creatorExecutionEpoch(),
                CURRENT_COPY_POLICY_VERSION,
                copyResult.contentSha256(),
                copyResult.fileCount(),
                copyResult.totalBytes(),
                createdAt.toString()
        );
    }
}
