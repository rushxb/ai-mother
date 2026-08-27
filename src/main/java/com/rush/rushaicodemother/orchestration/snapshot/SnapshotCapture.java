package com.rush.rushaicodemother.orchestration.snapshot;

import java.nio.file.Path;
import java.util.Objects;

/** 创建快照所需的最小可信输入。 */
public record SnapshotCapture(
        String snapshotName,
        SnapshotScope scope,
        Path sourceDirectory,
        SnapshotKind kind,
        String creatorTaskId,
        long creatorExecutionEpoch
) {

    public SnapshotCapture {
        snapshotName = requireText(snapshotName, "snapshotName");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        sourceDirectory = Objects.requireNonNull(sourceDirectory, "sourceDirectory must not be null")
                .toAbsolutePath()
                .normalize();
        kind = Objects.requireNonNull(kind, "kind must not be null");
        creatorTaskId = requireText(creatorTaskId, "creatorTaskId");
        if (creatorExecutionEpoch <= 0) {
            throw new IllegalArgumentException("creatorExecutionEpoch must be positive");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
