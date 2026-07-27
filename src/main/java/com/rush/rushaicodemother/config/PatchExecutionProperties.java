package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 本地补丁验证和文件变更的资源限制。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.patch-execution")
public class PatchExecutionProperties {

    @Min(1)
    @Max(1_000)
    private int maxOperations = 100;

    @Min(1_024)
    @Max(10_000_000)
    private int maxOperationContentChars = 1_000_000;

    @Min(1_024)
    @Max(50_000_000)
    private int maxTotalContentChars = 5_000_000;

    @Min(1_024)
    @Max(104_857_600L)
    private long maxReadableFileBytes = 5L * 1024 * 1024;

    @Min(1_024)
    @Max(104_857_600L)
    private long maxWrittenFileBytes = 10L * 1024 * 1024;

    @Min(1_024)
    @Max(524_288_000L)
    private long maxRollbackSnapshotBytes = 20L * 1024 * 1024;

    @AssertTrue(message = "The total patch content limit must not be smaller than the per-operation limit")
    public boolean isContentBudgetValid() {
        return maxTotalContentChars >= maxOperationContentChars;
    }

    @AssertTrue(message = "The rollback snapshot limit must not be smaller than the readable file limit")
    public boolean isRollbackBudgetValid() {
        return maxRollbackSnapshotBytes >= maxReadableFileBytes;
    }
}
