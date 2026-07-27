package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 有界工作区扫描、读取和快照复制的共享资源限制。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.workspace-file-system")
public class WorkspaceFileSystemProperties {

    @Min(1)
    @Max(1_000_000)
    private int maxFiles = 20_000;

    @Min(1)
    @Max(256)
    private int maxDirectoryDepth = 64;

    @Min(1_048_576L)
    private long maxScannedBytes = 2L * 1024 * 1024 * 1024;

    @Min(1_024L)
    private long maxFileBytes = 100L * 1024 * 1024;

    @Min(1_024L)
    @Max(104_857_600L)
    private long maxReadableFileBytes = 2L * 1024 * 1024;

    @Min(1_024L)
    @Max(104_857_600L)
    private long maxInteractiveFileBytes = 1L * 1024 * 1024;

    @Min(1)
    @Max(256)
    private int maxInteractiveTreeDepth = 8;

    @Min(1_048_576L)
    private long maxCopyBytes = 2L * 1024 * 1024 * 1024;

    @Min(1_048_576L)
    @Max(268_435_456L)
    private long maxPersistedFileBytes = 64L * 1024 * 1024;

    @Min(1)
    @Max(100_000)
    private int maxListedDirectories = 1_000;

    @Min(1)
    @Max(20)
    private int publishMaxAttempts = 5;

    @Min(0)
    @Max(5_000)
    private long publishRetryDelayMillis = 50;

    @AssertTrue(message = "工作区复制总字节上限必须大于等于单文件上限")
    public boolean isCopyLimitValid() {
        return maxCopyBytes >= maxFileBytes;
    }
}
