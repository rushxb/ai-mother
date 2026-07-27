package com.rush.rushaicodemother.orchestration.benchmark.worker;

import java.util.List;

/** 表示评测已完整执行，但候选未通过发布门禁。 */
public class GenerationBenchmarkWorkerRejectedException extends IllegalStateException {

    public GenerationBenchmarkWorkerRejectedException(List<String> violations) {
        super("Benchmark 候选未通过发布门禁: " + String.join(",", violations));
    }
}
