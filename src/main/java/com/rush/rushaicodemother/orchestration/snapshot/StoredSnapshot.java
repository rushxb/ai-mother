package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceDirectoryFingerprint;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** 已通过 manifest 与规范路径校验、可供恢复或比较的快照句柄。 */
public record StoredSnapshot(
        String snapshotName,
        String snapshotId,
        SnapshotScope scope,
        SnapshotKind kind,
        String creatorTaskId,
        long creatorExecutionEpoch,
        Path containerPath,
        Path payloadPath,
        WorkspaceDirectoryFingerprint fingerprint,
        String manifestSha256,
        Instant createdAt
) {

    public StoredSnapshot {
        snapshotName = Objects.requireNonNull(snapshotName, "snapshotName must not be null");
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        creatorTaskId = Objects.requireNonNull(creatorTaskId, "creatorTaskId must not be null");
        if (creatorExecutionEpoch <= 0) {
            throw new IllegalArgumentException("creatorExecutionEpoch must be positive");
        }
        containerPath = Objects.requireNonNull(containerPath, "containerPath must not be null")
                .toAbsolutePath().normalize();
        payloadPath = Objects.requireNonNull(payloadPath, "payloadPath must not be null")
                .toAbsolutePath().normalize();
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        manifestSha256 = Objects.requireNonNull(manifestSha256, "manifestSha256 must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
