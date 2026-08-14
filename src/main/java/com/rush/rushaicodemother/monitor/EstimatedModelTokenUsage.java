package com.rush.rushaicodemother.monitor;

/** Provider 未返回 usage 时的结构化估算结果。 */
public record EstimatedModelTokenUsage(
        int promptTokens,
        int completionTokens,
        int totalTokens
) {

    public EstimatedModelTokenUsage {
        if (promptTokens < 0 || completionTokens < 0
                || (long) promptTokens + completionTokens != totalTokens) {
            throw new IllegalArgumentException("estimated model token usage is inconsistent");
        }
    }
}
