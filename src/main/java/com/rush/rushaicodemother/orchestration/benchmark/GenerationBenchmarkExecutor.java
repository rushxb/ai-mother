package com.rush.rushaicodemother.orchestration.benchmark;

/**
 * 生成基准测试执行器。
 */
@FunctionalInterface
public interface GenerationBenchmarkExecutor {

    GenerationBenchmarkRunResult execute(GenerationBenchmarkTask task);
}
