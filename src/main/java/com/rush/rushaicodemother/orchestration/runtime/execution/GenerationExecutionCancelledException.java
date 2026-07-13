package com.rush.rushaicodemother.orchestration.runtime.execution;

/**
 * Raised when execution was cancelled by the user or the platform.
 */
public final class GenerationExecutionCancelledException extends GenerationExecutionPolicyException {

    public GenerationExecutionCancelledException(String reason) {
        super("生成任务已取消：" + (reason == null || reason.isBlank() ? "未提供原因" : reason));
    }
}
