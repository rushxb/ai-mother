package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable execution envelope shared by every generation pipeline.
 *
 * <p>The envelope makes task identity, cancellation, deadline and stream ownership explicit at
 * asynchronous boundaries. Pipelines must use this task identity instead of allocating their own.</p>
 */
public record GenerationTaskExecution(
        String taskId,
        GenerationSession session,
        GenerationExecutionContext executionContext,
        GenerationExecutionFence executionFence,
        Instant submittedAt
) {

    public GenerationTaskExecution {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("taskId format is invalid");
        }
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(executionContext, "executionContext");
        Objects.requireNonNull(executionFence, "executionFence");
        Objects.requireNonNull(submittedAt, "submittedAt");
        if (!taskId.equals(executionContext.taskId())) {
            throw new IllegalArgumentException("execution context taskId does not match envelope taskId");
        }
        if (!taskId.equals(executionFence.taskId())) {
            throw new IllegalArgumentException("execution fence taskId does not match envelope taskId");
        }
        if (!executionFence.equals(executionContext.executionFence())) {
            throw new IllegalArgumentException("execution context fence does not match envelope fence");
        }
    }
}
