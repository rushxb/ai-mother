package com.rush.rushaicodemother.service.devserver;

/** Dev Server 进程启动阶段的结构化异常。 */
final class DevServerStartException extends RuntimeException {

    enum Reason {
        INVALID_LAUNCHER,
        DEPENDENCY_INSTALL_FAILED,
        PROCESS_START_FAILED,
        PROCESS_EXITED,
        STARTUP_TIMEOUT,
        CANCELLED,
        INTERRUPTED
    }

    private final Reason reason;

    DevServerStartException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    DevServerStartException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
