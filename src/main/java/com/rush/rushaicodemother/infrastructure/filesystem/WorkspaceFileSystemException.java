package com.rush.rushaicodemother.infrastructure.filesystem;

import java.io.IOException;

/** 由有界工作区文件系统模块引发的安全、分类的故障。 */
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
        /** staging 目录的内容指纹与调用方信任的指纹不一致，不得发布或替换目标。 */
        CONTENT_FINGERPRINT_MISMATCH,
        TARGET_ALREADY_EXISTS,
        COPY_FAILED,
        REPLACE_FAILED,
        /** 目录已发生位移且无法确认激活或恢复结果，必须人工核对物理工作区。 */
        REPLACE_OUTCOME_UNKNOWN
    }
}
