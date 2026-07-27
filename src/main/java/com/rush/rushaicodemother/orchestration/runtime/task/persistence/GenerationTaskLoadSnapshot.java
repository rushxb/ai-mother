package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

/** 准入和路由策略使用的当前持久运行时负载。 */
public record GenerationTaskLoadSnapshot(
        int queuedTaskCount,
        int runningTaskCount,
        int waitingApprovalTaskCount
) {

    public static GenerationTaskLoadSnapshot empty() {
        return new GenerationTaskLoadSnapshot(0, 0, 0);
    }
}
