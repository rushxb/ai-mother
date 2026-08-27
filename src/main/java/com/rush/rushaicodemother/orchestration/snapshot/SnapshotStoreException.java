package com.rush.rushaicodemother.orchestration.snapshot;

import java.io.IOException;

/** 快照 store 的分类失败；调用方应根据 reason 做失败关闭或用户提示。 */
public class SnapshotStoreException extends IOException {

    private final Reason reason;

    public SnapshotStoreException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SnapshotStoreException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        ALREADY_EXISTS,
        AMBIGUOUS_NAME,
        NOT_FOUND,
        MANIFEST_MISSING,
        MANIFEST_INVALID,
        UNSUPPORTED_SCHEMA,
        PROVENANCE_MISMATCH,
        CONTENT_MISMATCH,
        STORAGE_FAILURE
    }
}
