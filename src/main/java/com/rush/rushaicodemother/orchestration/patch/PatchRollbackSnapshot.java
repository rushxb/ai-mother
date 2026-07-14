package com.rush.rushaicodemother.orchestration.patch;

import java.util.List;

/** Immutable pre-mutation state used to roll back a failed patch batch. */
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
