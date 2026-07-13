package com.rush.rushaicodemother.orchestration.runtime.execution;

/**
 * Raised when the task-wide deadline no longer permits starting or continuing an operation.
 */
public final class GenerationDeadlineExceededException extends GenerationExecutionPolicyException {

    public GenerationDeadlineExceededException(String taskId) {
        super("生成任务已超过总时限，taskId=" + taskId);
    }
}
