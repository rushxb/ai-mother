package com.rush.rushaicodemother.orchestration.dag;

/**
 * 当工作流检查点无法持久提交时引发。
 *
 * 当引发此异常时，<p>Callers 必须停止工作流程。失败后继续
 * 检查点可以在恢复后重复模型、工具、构建或工作区副作用。</p>
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
