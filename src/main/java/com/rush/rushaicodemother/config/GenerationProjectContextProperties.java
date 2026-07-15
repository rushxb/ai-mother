package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Resource limits for assembling generated-project context supplied to AI orchestration. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-project-context")
public class GenerationProjectContextProperties {

    /** Maximum number of project paths included in the model-facing index. */
    @Min(1)
    @Max(10_000)
    private int maxProjectIndexFiles = 80;

    /** Maximum number of characters contributed by one selected project file. */
    @Min(1_024)
    @Max(1_000_000)
    private int maxSingleFileChars = 12_000;

    /** Maximum total size of the assembled project context. */
    @Min(1_024)
    @Max(5_000_000)
    private int maxTotalContextChars = 100_000;

    /** Maximum file size accepted by the bounded workspace reader. */
    @Min(1_024L)
    @Max(104_857_600L)
    private long maxReadableFileBytes = 1_048_576L;

    @AssertTrue(message = "生成项目上下文总字符上限不得小于单文件字符上限")
    public boolean isContextBudgetValid() {
        return maxTotalContextChars >= maxSingleFileChars;
    }
}
