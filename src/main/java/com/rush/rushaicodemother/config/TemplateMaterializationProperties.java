package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Bounded classpath-template materialization limits. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.template-materialization")
public class TemplateMaterializationProperties {

    @Min(1)
    @Max(100_000)
    private int maxFiles = 2_000;

    @Min(1_024L)
    @Max(268_435_456L)
    private long maxFileBytes = 10L * 1024 * 1024;

    @Min(1_024L)
    @Max(2_147_483_648L)
    private long maxTotalBytes = 100L * 1024 * 1024;

    @Min(1)
    @Max(8_192)
    private int maxRelativePathLength = 1_024;

    @Min(1)
    @Max(256)
    private int maxDirectoryDepth = 32;

    @Min(1)
    @Max(20)
    private int publishMaxAttempts = 5;

    @Min(0)
    @Max(5_000)
    private long publishRetryDelayMillis = 50;

    @AssertTrue(message = "Template total-byte limit must be greater than or equal to the single-file limit")
    public boolean isTotalByteLimitValid() {
        return maxTotalBytes >= maxFileBytes;
    }
}
