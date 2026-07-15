package com.rush.rushaicodemother.service.artifact;

import java.io.IOException;

/** Classified failure raised by the bounded artifact copy boundary. */
class ArtifactCopyException extends IOException {

    enum Reason {
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


    Reason reason() {
        return reason;
    }
}
