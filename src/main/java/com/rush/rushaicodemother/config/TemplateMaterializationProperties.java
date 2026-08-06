package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 固定的类路径模板具体化限制。 */
@Data
@Component
@Validated
public class TemplateMaterializationProperties {

    public static final int MAX_FILES = 2_000;
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;
    public static final int MAX_RELATIVE_PATH_LENGTH = 1_024;
    public static final int MAX_DIRECTORY_DEPTH = 32;
    public static final int PUBLISH_MAX_ATTEMPTS = 5;
    public static final long PUBLISH_RETRY_DELAY_MILLIS = 50;

    @Min(1)
    @Max(100_000)
    private int maxFiles = MAX_FILES;

    @Min(1_024L)
    @Max(268_435_456L)
    private long maxFileBytes = MAX_FILE_BYTES;

    @Min(1_024L)
    @Max(2_147_483_648L)
    private long maxTotalBytes = MAX_TOTAL_BYTES;

    @Min(1)
    @Max(8_192)
    private int maxRelativePathLength = MAX_RELATIVE_PATH_LENGTH;

    @Min(1)
    @Max(256)
    private int maxDirectoryDepth = MAX_DIRECTORY_DEPTH;

    @Min(1)
    @Max(20)
    private int publishMaxAttempts = PUBLISH_MAX_ATTEMPTS;

    @Min(0)
    @Max(5_000)
    private long publishRetryDelayMillis = PUBLISH_RETRY_DELAY_MILLIS;

    @AssertTrue(message = "模板具体化总字节上限必须大于等于单文件上限")
    public boolean isTotalByteLimitValid() {
        return maxTotalBytes >= maxFileBytes;
    }
}
