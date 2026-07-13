package com.rush.rushaicodemother.infrastructure.process;

/** 受控外部进程的结构化执行结果。 */
public record ManagedProcessResult(
        Status status,
        String command,
        Integer exitCode,
        String stdout,
        String stderr,
        String errorDetail
) {

    public enum Status {
        COMPLETED,
        TIMED_OUT,
        IDLE_TIMED_OUT,
        INTERRUPTED,
        START_FAILED
    }

    public boolean completed() {
        return status == Status.COMPLETED;
    }

    public boolean exitedSuccessfully() {
        return completed() && Integer.valueOf(0).equals(exitCode);
    }

    public String combinedOutput() {
        if (stderr == null || stderr.isBlank()) {
            return stdout == null ? "" : stdout;
        }
        if (stdout == null || stdout.isBlank()) {
            return stderr;
        }
        return stdout + System.lineSeparator() + stderr;
    }
}
