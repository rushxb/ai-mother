package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 定位文件进行 AI 辅助编辑时使用的固定资源和上下文限制。 */
@Data
@Component
@Validated
public class EditLocatorProperties {

    public static final int MAX_CANDIDATE_FILES = 8;
    public static final int MAX_SINGLE_FILE_CHARS = 20 * 1024;
    public static final int MAX_TOTAL_CONTEXT_CHARS = 60 * 1024;
    public static final int MAX_SCANNED_FILES = 20_000;
    public static final long MAX_READABLE_FILE_BYTES = 2L * 1024 * 1024;
    public static final int MAX_PROJECT_INDEX_FILES = 80;

    @Min(1)
    @Max(100)
    private int maxCandidateFiles = MAX_CANDIDATE_FILES;

    @Min(1024)
    @Max(1_000_000)
    private int maxSingleFileChars = MAX_SINGLE_FILE_CHARS;

    @Min(1024)
    @Max(5_000_000)
    private int maxTotalContextChars = MAX_TOTAL_CONTEXT_CHARS;

    @Min(100)
    @Max(1_000_000)
    private int maxScannedFiles = MAX_SCANNED_FILES;

    @Min(1024)
    @Max(104_857_600L)
    private long maxReadableFileBytes = MAX_READABLE_FILE_BYTES;

    @Min(1)
    @Max(10_000)
    private int maxProjectIndexFiles = MAX_PROJECT_INDEX_FILES;

    @AssertTrue(message = "编辑上下文总字符上限不得小于单文件字符上限")
    public boolean isContextBudgetValid() {
        return maxTotalContextChars >= maxSingleFileChars;
    }
}
