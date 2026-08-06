package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 本地补丁验证和文件变更的固定资源限制。 */
@Data
@Component
@Validated
public class PatchExecutionProperties {

    public static final int MAX_OPERATIONS = 100;
    public static final int MAX_OPERATION_CONTENT_CHARS = 1_000_000;
    public static final int MAX_TOTAL_CONTENT_CHARS = 5_000_000;
    public static final long MAX_READABLE_FILE_BYTES = 5L * 1024 * 1024;
    public static final long MAX_WRITTEN_FILE_BYTES = 10L * 1024 * 1024;
    public static final long MAX_ROLLBACK_SNAPSHOT_BYTES = 20L * 1024 * 1024;

    @Min(1)
    @Max(1_000)
    private int maxOperations = MAX_OPERATIONS;

    @Min(1_024)
    @Max(10_000_000)
    private int maxOperationContentChars = MAX_OPERATION_CONTENT_CHARS;

    @Min(1_024)
    @Max(50_000_000)
    private int maxTotalContentChars = MAX_TOTAL_CONTENT_CHARS;

    @Min(1_024)
    @Max(104_857_600L)
    private long maxReadableFileBytes = MAX_READABLE_FILE_BYTES;

    @Min(1_024)
    @Max(104_857_600L)
    private long maxWrittenFileBytes = MAX_WRITTEN_FILE_BYTES;

    @Min(1_024)
    @Max(524_288_000L)
    private long maxRollbackSnapshotBytes = MAX_ROLLBACK_SNAPSHOT_BYTES;

    @AssertTrue(message = "补丁总内容上限不得小于单次操作内容上限")
    public boolean isContentBudgetValid() {
        return maxTotalContentChars >= maxOperationContentChars;
    }

    @AssertTrue(message = "回滚快照上限不得小于可读文件上限")
    public boolean isRollbackBudgetValid() {
        return maxRollbackSnapshotBytes >= maxReadableFileBytes;
    }
}
