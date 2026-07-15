package com.rush.rushaicodemother.orchestration.workspace;

/**
 * Describes why an artifact-reported workspace could not be resolved to a canonical application directory.
 */
public final class ReportedWorkspaceResolutionException extends RuntimeException {

    private final Reason reason;

    public ReportedWorkspaceResolutionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ReportedWorkspaceResolutionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        CONTEXT_MISMATCH,
        UNSAFE_WORKSPACE,
        WORKSPACE_UNAVAILABLE
    }
}
