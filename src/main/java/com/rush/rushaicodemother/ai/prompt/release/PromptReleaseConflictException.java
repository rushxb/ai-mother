package com.rush.rushaicodemother.ai.prompt.release;

/** 表示管理员尝试从过时的版本修订版进行发布。 */
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
