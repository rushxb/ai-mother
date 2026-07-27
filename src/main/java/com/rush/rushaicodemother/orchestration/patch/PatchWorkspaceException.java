package com.rush.rushaicodemother.orchestration.patch;

import java.io.IOException;

/** 已检查当补丁目标违反工作空间或资源限制时引发的故障。 */
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
