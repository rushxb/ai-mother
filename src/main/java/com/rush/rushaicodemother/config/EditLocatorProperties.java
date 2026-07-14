package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Resource and context limits used while locating files for AI-assisted edits. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.edit-locator")
public class EditLocatorProperties {

    @Min(1)
    @Max(100)
    private int maxCandidateFiles = 8;

    @Min(1024)
    @Max(1_000_000)
    private int maxSingleFileChars = 20 * 1024;

    @Min(1024)
    @Max(5_000_000)
    private int maxTotalContextChars = 60 * 1024;

    @Min(100)
    @Max(1_000_000)
    private int maxScannedFiles = 20_000;

    @Min(1024)
    @Max(104_857_600L)
    private long maxReadableFileBytes = 2L * 1024 * 1024;

    @Min(1)
    @Max(10_000)
    private int maxProjectIndexFiles = 80;

    @AssertTrue(message = "The total edit context limit must not be smaller than the single-file limit")
    public boolean isContextBudgetValid() {
        return maxTotalContextChars >= maxSingleFileChars;
    }
}
