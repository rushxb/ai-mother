package com.rush.rushaicodemother.orchestration.runtime.model;

import java.util.concurrent.TimeoutException;

/** 模型调用监督器主动终止单个逻辑回合时使用的类型化超时。 */
public final class GenerationModelCallTimeoutException extends TimeoutException {

    private final String timeoutKind;

    public GenerationModelCallTimeoutException(String timeoutKind) {
        super("模型调用超过监督器限制，类型=" + normalize(timeoutKind));
        this.timeoutKind = normalize(timeoutKind);
    }

    public String timeoutKind() {
        return timeoutKind;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
