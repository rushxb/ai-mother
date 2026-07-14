package com.rush.rushaicodemother.orchestration.patch;

import java.io.IOException;

/** Failure raised after patch mutation started and rollback was attempted. */
public class PatchBatchExecutionException extends IOException {

    public PatchBatchExecutionException(Throwable cause) {
        super("patch_apply_failed", cause);
    }
}
