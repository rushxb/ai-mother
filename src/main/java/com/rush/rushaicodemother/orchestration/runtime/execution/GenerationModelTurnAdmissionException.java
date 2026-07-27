package com.rush.rushaicodemother.orchestration.runtime.execution;

import java.time.Duration;

/**
 * 表示模型阶段必须停止扩展，把剩余时间交还给构建、验证和终态收口。
 */
public final class GenerationModelTurnAdmissionException extends GenerationDeadlineExceededException {

    private final Duration remaining;
    private final Duration required;
    private final Duration completionReserve;

    public GenerationModelTurnAdmissionException(String taskId,
                                                 Duration remaining,
                                                 Duration required,
                                                 Duration completionReserve) {
        super(taskId, "model_turn", remaining, required);
        this.remaining = nonNegative(remaining);
        this.required = nonNegative(required);
        this.completionReserve = nonNegative(completionReserve);
    }

    public Duration remaining() {
        return remaining;
    }

    public Duration required() {
        return required;
    }

    public Duration completionReserve() {
        return completionReserve;
    }

    private static Duration nonNegative(Duration value) {
        return value == null || value.isNegative() ? Duration.ZERO : value;
    }
}
