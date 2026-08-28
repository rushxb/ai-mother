package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

/**
 * Benchmark 评分阶段消费的持久任务执行事实。
 *
 * <p>目标类型与回退原因均来自已持久化任务命令，不从夹具 App、Prompt 或进程内遥测反推。
 * 这样即使 Benchmark 与执行 Worker 不在同一实例，评分仍能恢复相同事实。</p>
 */
public record GenerationBenchmarkExecutionFacts(
        String taskId,
        Long appId,
        CodeGenTypeEnum targetType,
        String fallbackReason
) {

    public GenerationBenchmarkExecutionFacts {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Benchmark 执行任务身份无效");
        }
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("Benchmark 执行应用身份无效");
        }
        if (targetType == null) {
            throw new IllegalArgumentException("Benchmark 执行目标工程类型不能为空");
        }
        fallbackReason = fallbackReason == null ? "" : fallbackReason.trim();
    }

    /** 是否由持久命令证明本次执行发生过路由回退。 */
    public boolean fallbackObserved() {
        return !fallbackReason.isBlank();
    }
}
