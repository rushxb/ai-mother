package com.rush.rushaicodemother.infrastructure.process;

/** Controls how process output is exposed to application logs without affecting bounded result capture. */
public enum ManagedProcessOutputLogPolicy {

    /** Emit each completed process-output line at INFO level. */
    STREAM(true, true),

    /** Keep output in the bounded result only; heartbeats and timeout logs contain metadata, not output content. */
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
