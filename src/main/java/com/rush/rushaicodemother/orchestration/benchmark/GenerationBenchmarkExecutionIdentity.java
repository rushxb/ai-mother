package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

/**
 * Benchmark 评分阶段消费的持久任务执行身份。
 *
 * <p>该身份来自已持久化任务命令，不从夹具 App 或 Prompt 反推，因而在跨类型升级
 * 和路由回退后仍能精确定位本次任务拥有的发布制品。</p>
 */
public record GenerationBenchmarkExecutionIdentity(
        String taskId,
        Long appId,
        CodeGenTypeEnum targetType
) {

    public GenerationBenchmarkExecutionIdentity {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Benchmark 执行任务身份无效");
        }
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("Benchmark 执行应用身份无效");
        }
        if (targetType == null) {
            throw new IllegalArgumentException("Benchmark 执行目标工程类型不能为空");
        }
    }
}
