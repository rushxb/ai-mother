package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** AI 工具执行文件系统访问时使用的固定资源限制。 */
@Data
@Component
@Validated
public class AiToolWorkspaceProperties {

    public static final long MAX_READABLE_FILE_BYTES = 1L * 1024 * 1024;
    public static final int MAX_DIRECTORY_ENTRIES = 5_000;
    public static final int MAX_DIRECTORY_DEPTH = 32;
    public static final int MAX_BATCH_WRITE_FILES = 20;
    public static final int MAX_BATCH_WRITE_TOTAL_CHARS = 500_000;

    @Min(1_024)
    @Max(10_485_760L)
    private long maxReadableFileBytes = MAX_READABLE_FILE_BYTES;

    @Min(1)
    @Max(100_000)
    private int maxDirectoryEntries = MAX_DIRECTORY_ENTRIES;

    @Min(1)
    @Max(128)
    private int maxDirectoryDepth = MAX_DIRECTORY_DEPTH;

    @Min(1)
    @Max(100)
    private int maxBatchWriteFiles = MAX_BATCH_WRITE_FILES;

    @Min(1_024)
    @Max(5_000_000)
    private int maxBatchWriteTotalChars = MAX_BATCH_WRITE_TOTAL_CHARS;
}
