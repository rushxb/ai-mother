package com.rush.rushaicodemother.orchestration.benchmark;

/** 来自持久生成跟踪的只读基准使用情况投影。 */
public interface GenerationBenchmarkUsageRepository {
    GenerationBenchmarkUsage findByTaskId(String taskId);
}
