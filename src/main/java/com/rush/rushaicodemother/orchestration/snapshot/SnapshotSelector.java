package com.rush.rushaicodemother.orchestration.snapshot;

import java.util.Objects;

/**
 * 快照选择器。
 *
 * <p>手工操作至少绑定工作区 scope；持久回滚点还会携带不可变 snapshotId、用途、
 * 创建任务和 manifest 摘要，防止同名目录被删除重建后复用旧事实。</p>
 */
public record SnapshotSelector(
        String snapshotName,
        SnapshotScope scope,
        String expectedSnapshotId,
        SnapshotKind expectedKind,
        String expectedCreatorTaskId,
        Long expectedCreatorExecutionEpoch,
        String expectedManifestSha256
) {

    public SnapshotSelector {
        if (snapshotName == null || snapshotName.isBlank()) {
            throw new IllegalArgumentException("snapshotName must not be blank");
        }
        snapshotName = snapshotName.trim();
        scope = Objects.requireNonNull(scope, "scope must not be null");
        expectedSnapshotId = normalizeOptional(expectedSnapshotId);
        expectedCreatorTaskId = normalizeOptional(expectedCreatorTaskId);
        if (expectedCreatorExecutionEpoch != null && expectedCreatorExecutionEpoch <= 0) {
            throw new IllegalArgumentException("expectedCreatorExecutionEpoch must be positive");
        }
        expectedManifestSha256 = normalizeOptional(expectedManifestSha256).toLowerCase();
    }

    public static SnapshotSelector forWorkspace(String snapshotName, SnapshotScope scope) {
        return new SnapshotSelector(snapshotName, scope, "", null, "", null, "");
    }

    public static SnapshotSelector exact(StoredSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return new SnapshotSelector(
                snapshot.snapshotName(),
                snapshot.scope(),
                snapshot.snapshotId(),
                snapshot.kind(),
                snapshot.creatorTaskId(),
                snapshot.creatorExecutionEpoch(),
                snapshot.manifestSha256()
        );
    }

    public static SnapshotSelector persisted(String snapshotName,
                                               SnapshotScope scope,
                                               String snapshotId,
                                               SnapshotKind kind,
                                               String creatorTaskId,
                                               long creatorExecutionEpoch,
                                               String manifestSha256) {
        return new SnapshotSelector(
                snapshotName,
                scope,
                snapshotId,
                Objects.requireNonNull(kind, "kind must not be null"),
                creatorTaskId,
                creatorExecutionEpoch,
                manifestSha256
        );
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }
}
