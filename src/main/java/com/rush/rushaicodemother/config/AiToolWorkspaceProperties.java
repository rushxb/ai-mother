package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** AI 工具执行的文件系统访问的资源限制。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai-tool-workspace")
public class AiToolWorkspaceProperties {

    @Min(1_024)
    @Max(10_485_760L)
    private long maxReadableFileBytes = 1L * 1024 * 1024;

    @Min(1)
    @Max(100_000)
    private int maxDirectoryEntries = 5_000;

    @Min(1)
    @Max(128)
    private int maxDirectoryDepth = 32;

    @Min(1)
    @Max(100)
    private int maxBatchWriteFiles = 20;

    @Min(1_024)
    @Max(5_000_000)
    private int maxBatchWriteTotalChars = 500_000;
}
