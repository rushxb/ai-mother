package com.rush.rushaicodemother.orchestration.patch;

import java.io.IOException;

/** 补丁变更开始并尝试回滚后出现故障。 */
public class PatchBatchExecutionException extends IOException {

    public PatchBatchExecutionException(Throwable cause) {
        super("patch_apply_failed", cause);
    }
}
