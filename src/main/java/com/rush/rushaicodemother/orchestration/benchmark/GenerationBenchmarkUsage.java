package com.rush.rushaicodemother.orchestration.benchmark;

public record GenerationBenchmarkUsage(long totalTokens, long creditCost) {
    public GenerationBenchmarkUsage {
        totalTokens = Math.max(0, totalTokens);
        creditCost = Math.max(0, creditCost);
    }

    public static GenerationBenchmarkUsage empty() {
        return new GenerationBenchmarkUsage(0, 0);
    }
}
