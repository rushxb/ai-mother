package com.rush.rushaicodemother.orchestration.benchmark;

import java.util.Optional;

/** 为 Benchmark 暴露持久任务执行事实的只读接口。 */
public interface GenerationBenchmarkExecutionFactsProvider {

    /** 按任务身份读取提交阶段冻结且可跨实例恢复的执行事实。 */
    Optional<GenerationBenchmarkExecutionFacts> findByTaskId(String taskId);
}
