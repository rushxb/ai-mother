package com.rush.rushaicodemother.orchestration.benchmark;

/**
 * 生成基准测试用量的不可变数据载体。
 */
public record GenerationBenchmarkUsage(long totalTokens, long creditCost) {
    public GenerationBenchmarkUsage {
        totalTokens = Math.max(0, totalTokens);
        creditCost = Math.max(0, creditCost);
    }

    public static GenerationBenchmarkUsage empty() {
        return new GenerationBenchmarkUsage(0, 0);
    }
}
