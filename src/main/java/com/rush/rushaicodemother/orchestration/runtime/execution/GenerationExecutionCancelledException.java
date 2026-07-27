package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.core.error.GenerationCancellationSignal;

/**
 * 当用户或平台取消执行时引发。
 */
public final class GenerationExecutionCancelledException extends GenerationExecutionPolicyException
        implements GenerationCancellationSignal {

    public GenerationExecutionCancelledException(String reason) {
        super("生成任务已取消：" + (reason == null || reason.isBlank() ? "未提供原因" : reason));
    }
}
