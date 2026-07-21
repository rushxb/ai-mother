package com.rush.rushaicodemother.orchestration.dag;

/**
 * Raised when a workflow checkpoint cannot be durably committed.
 *
 * <p>Callers must stop the workflow when this exception is raised. Continuing after a failed
 * checkpoint can repeat model, tool, build, or workspace side effects after recovery.</p>
 */
public final class GenerationCheckpointPersistenceException extends IllegalStateException {

    private final Reason reason;

    public GenerationCheckpointPersistenceException(Reason reason, String message) {
        super(message);
        this.reason = requireReason(reason);
    }

    public GenerationCheckpointPersistenceException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = requireReason(reason);
    }

    public Reason reason() {
        return reason;
    }

    private static Reason requireReason(Reason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("checkpoint failure reason is required");
        }
        return reason;
    }

    public enum Reason {
        INVALID_IDENTITY,
        SNAPSHOT_TOO_LARGE,
        STALE_EXECUTION_FENCE,
        STORAGE_FAILURE
    }
}
