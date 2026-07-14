package com.rush.rushaicodemother.orchestration.patch;

import java.io.IOException;

/** Checked failure raised when a patch target violates workspace or resource constraints. */
public class PatchWorkspaceException extends IOException {

    private final String reason;

    public PatchWorkspaceException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public PatchWorkspaceException(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
