package com.rush.rushaicodemother.orchestration.benchmark;

import java.util.Optional;

/** 为 Benchmark 提供已持久化任务的执行身份，隔离具体任务存储实现。 */
public interface GenerationBenchmarkExecutionIdentityProvider {

    /** 按任务编号查询冻结后的执行身份；不存在或不可恢复时返回空。 */
    Optional<GenerationBenchmarkExecutionIdentity> findByTaskId(String taskId);
}
