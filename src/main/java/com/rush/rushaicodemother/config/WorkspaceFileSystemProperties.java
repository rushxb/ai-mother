package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 工作区扫描、读取和快照复制的固定共享资源限制。 */
@Data
@Component
@Validated
public class WorkspaceFileSystemProperties {

    public static final int MAX_FILES = 20_000;
    public static final int MAX_DIRECTORY_DEPTH = 64;
    public static final long MAX_SCANNED_BYTES = 2L * 1024 * 1024 * 1024;
    public static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    public static final long MAX_READABLE_FILE_BYTES = 2L * 1024 * 1024;
    public static final long MAX_INTERACTIVE_FILE_BYTES = 1L * 1024 * 1024;
    public static final int MAX_INTERACTIVE_TREE_DEPTH = 8;
    public static final long MAX_COPY_BYTES = 2L * 1024 * 1024 * 1024;
    public static final long MAX_PERSISTED_FILE_BYTES = 64L * 1024 * 1024;
    public static final int MAX_LISTED_DIRECTORIES = 1_000;
    public static final int PUBLISH_MAX_ATTEMPTS = 5;
    public static final long PUBLISH_RETRY_DELAY_MILLIS = 50;

    @Min(1)
    @Max(1_000_000)
    private int maxFiles = MAX_FILES;

    @Min(1)
    @Max(256)
    private int maxDirectoryDepth = MAX_DIRECTORY_DEPTH;

    @Min(1_048_576L)
    private long maxScannedBytes = MAX_SCANNED_BYTES;

    @Min(1_024L)
    private long maxFileBytes = MAX_FILE_BYTES;

    @Min(1_024L)
    @Max(104_857_600L)
    private long maxReadableFileBytes = MAX_READABLE_FILE_BYTES;

    @Min(1_024L)
    @Max(104_857_600L)
    private long maxInteractiveFileBytes = MAX_INTERACTIVE_FILE_BYTES;

    @Min(1)
    @Max(256)
    private int maxInteractiveTreeDepth = MAX_INTERACTIVE_TREE_DEPTH;

    @Min(1_048_576L)
    private long maxCopyBytes = MAX_COPY_BYTES;

    @Min(1_048_576L)
    @Max(268_435_456L)
    private long maxPersistedFileBytes = MAX_PERSISTED_FILE_BYTES;

    @Min(1)
    @Max(100_000)
    private int maxListedDirectories = MAX_LISTED_DIRECTORIES;

    @Min(1)
    @Max(20)
    private int publishMaxAttempts = PUBLISH_MAX_ATTEMPTS;

    @Min(0)
    @Max(5_000)
    private long publishRetryDelayMillis = PUBLISH_RETRY_DELAY_MILLIS;

    @AssertTrue(message = "工作区复制总字节上限必须大于等于单文件上限")
    public boolean isCopyLimitValid() {
        return maxCopyBytes >= maxFileBytes;
    }
}
