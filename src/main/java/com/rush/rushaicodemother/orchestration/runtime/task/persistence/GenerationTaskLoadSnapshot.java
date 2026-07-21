package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

/** Current durable runtime load used by admission and routing policies. */
public record GenerationTaskLoadSnapshot(
        int queuedTaskCount,
        int runningTaskCount,
        int waitingApprovalTaskCount
) {

    public static GenerationTaskLoadSnapshot empty() {
        return new GenerationTaskLoadSnapshot(0, 0, 0);
    }
}
