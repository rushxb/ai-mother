package com.rush.rushaicodemother.orchestration.runtime.task;

/** Executes submitted generation work outside the HTTP request thread. */
public interface GenerationTaskExecutor {

    void execute(String taskId, Runnable task);
}
