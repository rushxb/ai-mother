package com.rush.rushaicodemother.orchestration.runtime.task;

/** Executes submitted generation work outside the HTTP request thread. */
public interface GenerationTaskExecutor {

    void execute(String taskId, Runnable task);

    /**
     * Executes runtime-managed work with access to its cancellation and deadline envelope.
     *
     * <p>The default keeps compatibility with infrastructure adapters that only need task identity.
     * Deadline-aware local executors should override this method.</p>
     */
    default void execute(GenerationTaskExecution execution, Runnable task) {
        if (execution == null) {
            throw new IllegalArgumentException("generation task execution cannot be null");
        }
        execute(execution.taskId(), task);
    }
}
