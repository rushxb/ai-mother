package com.rush.rushaicodemother.orchestration.benchmark;

@FunctionalInterface
public interface GenerationBenchmarkExecutor {

    GenerationBenchmarkRunResult execute(GenerationBenchmarkTask task);
}
