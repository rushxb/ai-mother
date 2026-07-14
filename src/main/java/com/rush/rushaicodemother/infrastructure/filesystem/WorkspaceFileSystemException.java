package com.rush.rushaicodemother.infrastructure.filesystem;

import java.io.IOException;

/** Safe, classified failure raised by the bounded workspace file-system module. */
public class WorkspaceFileSystemException extends IOException {

    private final Reason reason;

    public WorkspaceFileSystemException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public WorkspaceFileSystemException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_PATH,
        MISSING_DIRECTORY,
        MISSING_FILE,
        NOT_REGULAR_FILE,
        UNSAFE_SYMBOLIC_LINK,
        FILE_LIMIT_EXCEEDED,
        BYTE_LIMIT_EXCEEDED,
        FILE_TOO_LARGE,
        FILE_CHANGED,
        TARGET_ALREADY_EXISTS,
        COPY_FAILED,
        REPLACE_FAILED
    }
}
