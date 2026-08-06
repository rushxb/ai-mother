package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 用于组装提供给 AI 编排的生成项目上下文的固定资源限制。 */
@Data
@Component
@Validated
public class GenerationProjectContextProperties {

    public static final int MAX_PROJECT_INDEX_FILES = 80;
    public static final int MAX_SINGLE_FILE_CHARS = 1_400;
    public static final int MAX_TOTAL_CONTEXT_CHARS = 10_000;
    public static final long MAX_READABLE_FILE_BYTES = 1_048_576L;

    /** 面向模型的索引中包含的项目路径的最大数量。 */
    @Min(1)
    @Max(10_000)
    private int maxProjectIndexFiles = MAX_PROJECT_INDEX_FILES;

    /** 一个选定项目文件贡献的最大字符数。 */
    @Min(1_024)
    @Max(1_000_000)
    private int maxSingleFileChars = MAX_SINGLE_FILE_CHARS;

    /** 组装项目上下文的最大总大小。 */
    @Min(1_024)
    @Max(5_000_000)
    private int maxTotalContextChars = MAX_TOTAL_CONTEXT_CHARS;

    /** 有界工作区读取器接受的最大文件大小。 */
    @Min(1_024L)
    @Max(104_857_600L)
    private long maxReadableFileBytes = MAX_READABLE_FILE_BYTES;

    @AssertTrue(message = "生成项目上下文总字符上限不得小于单文件字符上限")
    public boolean isContextBudgetValid() {
        return maxTotalContextChars >= maxSingleFileChars;
    }
}
