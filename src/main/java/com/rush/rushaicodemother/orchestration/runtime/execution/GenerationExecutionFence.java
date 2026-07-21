package com.rush.rushaicodemother.orchestration.runtime.execution;

/**
 * Immutable worker identity used to reject side effects from an expired generation execution.
 *
 * <p>The epoch is monotonically increased by the durable task store whenever execution ownership
 * is issued or revoked. A worker write is valid only while all three values still match the
 * persisted lease.</p>
 */
public record GenerationExecutionFence(
        String taskId,
        String leaseOwner,
        long executionEpoch
) {

    public GenerationExecutionFence {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("taskId format is invalid");
        }
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner cannot be blank");
        }
        if (executionEpoch <= 0) {
            throw new IllegalArgumentException("executionEpoch must be positive");
        }
    }
}
