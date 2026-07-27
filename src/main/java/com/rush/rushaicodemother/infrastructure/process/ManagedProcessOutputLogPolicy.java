package com.rush.rushaicodemother.infrastructure.process;

/** 控制如何将进程输出公开给应用程序日志，而不影响有限结果捕获。 */
public enum ManagedProcessOutputLogPolicy {

    /** 在 INFO 级别发出每个已完成的过程输出行。 */
    STREAM(true, true),

    /** 仅将输出保留在有界结果中；心跳和超时日志包含元数据，而不是输出内容。 */
    SUMMARY(false, false);

    private final boolean lineLoggingEnabled;
    private final boolean heartbeatTailEnabled;

    ManagedProcessOutputLogPolicy(boolean lineLoggingEnabled, boolean heartbeatTailEnabled) {
        this.lineLoggingEnabled = lineLoggingEnabled;
        this.heartbeatTailEnabled = heartbeatTailEnabled;
    }

    public boolean isLineLoggingEnabled() {
        return lineLoggingEnabled;
    }

    public boolean isHeartbeatTailEnabled() {
        return heartbeatTailEnabled;
    }
}
