package com.rush.rushaicodemother.orchestration.runtime.task;

/** Dispatches one already-persisted generation command to the configured worker transport. */
public interface GenerationTaskDispatcher {
    void dispatch(String taskId);
}
