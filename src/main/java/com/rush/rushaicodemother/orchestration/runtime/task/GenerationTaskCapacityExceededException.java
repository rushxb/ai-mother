package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.ServiceUnavailableException;

/** 当本地生成工作池无法接受额外工作时引发。 */
public final class GenerationTaskCapacityExceededException extends ServiceUnavailableException {

    private static final String PUBLIC_MESSAGE = "当前生成任务较多，请稍后重试";

    public GenerationTaskCapacityExceededException(String diagnosticMessage) {
        super(PUBLIC_MESSAGE, diagnosticMessage);
    }
}
