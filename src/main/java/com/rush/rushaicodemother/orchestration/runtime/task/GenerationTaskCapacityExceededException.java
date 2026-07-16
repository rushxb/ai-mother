package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.ServiceUnavailableException;

/** Raised when the local generation worker pool cannot accept additional work. */
public final class GenerationTaskCapacityExceededException extends ServiceUnavailableException {

    private static final String PUBLIC_MESSAGE = "当前生成任务较多，请稍后重试";

    public GenerationTaskCapacityExceededException(String diagnosticMessage) {
        super(PUBLIC_MESSAGE, diagnosticMessage);
    }
}
