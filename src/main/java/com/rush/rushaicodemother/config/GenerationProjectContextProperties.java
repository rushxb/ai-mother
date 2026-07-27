package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 用于组装提供给 AI 编排的生成项目上下文的资源限制。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-project-context")
public class GenerationProjectContextProperties {

    /** 面向模型的索引中包含的项目路径的最大数量。 */
    @Min(1)
    @Max(10_000)
    private int maxProjectIndexFiles = 80;

    /** 一个选定项目文件贡献的最大字符数。 */
    @Min(1_024)
    @Max(1_000_000)
    private int maxSingleFileChars = 1_400;

    /** 组装项目上下文的最大总大小。 */
    @Min(1_024)
    @Max(5_000_000)
    private int maxTotalContextChars = 10_000;

    /** 有界工作区读取器接受的最大文件大小。 */
    @Min(1_024L)
    @Max(104_857_600L)
    private long maxReadableFileBytes = 1_048_576L;

    @AssertTrue(message = "生成项目上下文总字符上限不得小于单文件字符上限")
    public boolean isContextBudgetValid() {
        return maxTotalContextChars >= maxSingleFileChars;
    }
}
