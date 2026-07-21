package com.rush.rushaicodemother.orchestration.runtime.task;

/** Enforces the absolute deadline of one runtime-managed generation task. */
public interface GenerationTaskWatchdog {

    Registration watch(GenerationTaskExecution execution, Runnable interruptRunningTask);

    /** Cancels a pending deadline callback after the worker has reached a terminal boundary. */
    @FunctionalInterface
    interface Registration extends AutoCloseable {

        @Override
        void close();
    }
}
