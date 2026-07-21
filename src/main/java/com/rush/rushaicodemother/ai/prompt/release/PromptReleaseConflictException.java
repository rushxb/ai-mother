package com.rush.rushaicodemother.ai.prompt.release;

/** Signals that an administrator attempted to publish from a stale release revision. */
public class PromptReleaseConflictException extends RuntimeException {

    private final long expectedRevision;
    private final long actualRevision;

    public PromptReleaseConflictException(long expectedRevision, long actualRevision) {
        super("prompt release revision conflict");
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
    }

    public long expectedRevision() {
        return expectedRevision;
    }

    public long actualRevision() {
        return actualRevision;
    }
}
