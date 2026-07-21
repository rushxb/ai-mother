package com.rush.rushaicodemother.service.artifact;

import java.io.IOException;

/** Classified failure raised by the bounded artifact copy boundary. */
public class ArtifactCopyException extends IOException {

    public enum Reason {
        CANCELLED,
        TIMED_OUT,
        INTERRUPTED,
        LIMIT_EXCEEDED,
        UNSAFE_SYMBOLIC_LINK,
        SOURCE_CHANGED,
        INCOMPLETE_COPY,
        INVALID_PATH
    }

    private final Reason reason;

    ArtifactCopyException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }


    public Reason reason() {
        return reason;
    }
}
