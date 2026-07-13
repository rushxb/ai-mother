package com.rush.rushaicodemother.infrastructure.process;

/** 项目命令的结构化执行结果。 */
public record ProjectCommandResult(
        Status status,
        String command,
        Integer exitCode,
        String output,
        String errorDetail
) {

    public enum Status {
        SUCCESS,
        FAILED,
        TIMED_OUT,
        IDLE_TIMED_OUT,
        INTERRUPTED,
        START_FAILED
    }

    public boolean success() {
        return status == Status.SUCCESS;
    }

    public boolean timedOut() {
        return status == Status.TIMED_OUT || status == Status.IDLE_TIMED_OUT;
    }
}
