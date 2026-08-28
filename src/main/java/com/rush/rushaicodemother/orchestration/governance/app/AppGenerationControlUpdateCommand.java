package com.rush.rushaicodemother.orchestration.governance.app;

import java.util.Objects;

/** 管理员提交的完整应用生成控制替换命令。 */
public record AppGenerationControlUpdateCommand(
        long expectedVersion,
        boolean generationPaused,
        boolean emergencyStopped,
        int maxConcurrentTasks,
        AppGenerationControlPolicy.ModelPolicy modelPolicy,
        AppGenerationControlPolicy.DependencyMutationPolicy dependencyMutationPolicy,
        AppGenerationControlPolicy.DependencyNetworkPolicy dependencyNetworkPolicy,
        AppGenerationControlPolicy.DangerousToolPolicy dangerousToolPolicy,
        Long monthlyCreditLimit
) {
    public AppGenerationControlUpdateCommand {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("期望版本不能为负数");
        }
        if (maxConcurrentTasks < 1
                || maxConcurrentTasks > AppGenerationControlPolicy.MAX_SAFE_CONCURRENT_TASKS) {
            throw new IllegalArgumentException("当前应用生成并发上限只能为 1");
        }
        Objects.requireNonNull(modelPolicy, "模型策略不能为空");
        Objects.requireNonNull(dependencyMutationPolicy, "依赖修改策略不能为空");
        Objects.requireNonNull(dependencyNetworkPolicy, "依赖网络策略不能为空");
        Objects.requireNonNull(dangerousToolPolicy, "危险工具策略不能为空");
        if (monthlyCreditLimit != null && monthlyCreditLimit < 0) {
            throw new IllegalArgumentException("应用月预算不能为负数");
        }
    }
}
