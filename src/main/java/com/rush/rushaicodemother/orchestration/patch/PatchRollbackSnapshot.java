package com.rush.rushaicodemother.orchestration.patch;

import java.util.List;

/** 用于回滚失败的补丁批次的不可变变更前状态。 */
public record PatchRollbackSnapshot(List<FileState> files) {

    public PatchRollbackSnapshot {
        files = files == null ? List.of() : List.copyOf(files);
    }

    public record FileState(
            PatchWorkspaceTarget target,
            boolean existed,
            String content
    ) {
    }
}
