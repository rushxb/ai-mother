package com.rush.rushaicodemother.orchestration.benchmark;

/** Read-only benchmark usage projection from the durable generation trace. */
public interface GenerationBenchmarkUsageRepository {
    GenerationBenchmarkUsage findByTaskId(String taskId);
}
