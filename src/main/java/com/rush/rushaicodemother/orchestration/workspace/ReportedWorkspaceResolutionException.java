package com.rush.rushaicodemother.orchestration.workspace;

/**
 * 描述为什么工件报告的工作空间无法解析为规范的应用程序目录。
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
