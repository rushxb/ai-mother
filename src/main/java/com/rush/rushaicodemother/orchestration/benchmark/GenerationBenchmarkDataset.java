package com.rush.rushaicodemother.orchestration.benchmark;

import java.util.List;

/** 不可变的版本化生成质量评测数据集。 */
public record GenerationBenchmarkDataset(
        int schemaVersion,
        String datasetId,
        String version,
        List<GenerationBenchmarkTask> tasks
) {
    public GenerationBenchmarkDataset {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
