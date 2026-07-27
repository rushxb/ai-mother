package com.rush.rushaicodemother.orchestration.template;

import java.io.IOException;

/** 验证或具体化打包项目模板时引发类型失败。 */
public class TemplateMaterializationException extends IOException {

    private final Reason reason;

    public TemplateMaterializationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public TemplateMaterializationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_TEMPLATE,
        INVALID_RESOURCE_PATH,
        UNSAFE_TARGET,
        TARGET_ALREADY_EXISTS,
        DUPLICATE_RESOURCE,
        FILE_LIMIT_EXCEEDED,
        FILE_TOO_LARGE,
        TOTAL_BYTES_EXCEEDED,
        EMPTY_TEMPLATE,
        COPY_FAILED
    }
}
