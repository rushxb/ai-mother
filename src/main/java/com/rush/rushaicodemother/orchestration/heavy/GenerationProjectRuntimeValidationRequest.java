package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 工程运行时验证所需的稳定上下文。
 *
 * <p>请求不暴露 Heavy 会话实现，只携带 adapter 真正需要的身份、工作区、执行栅栏
 * 和前端就绪回调，避免验证实现反向依赖编排主流程。</p>
 */
public record GenerationProjectRuntimeValidationRequest(
        String taskId,
        Long appId,
        Long userId,
        GenerationWorkspace workspace,
        GenerationExecutionFence executionFence,
        Duration maximumDuration,
        BooleanSupplier cancellationRequested,
        Runnable onFrontendReady
) {

    public GenerationProjectRuntimeValidationRequest {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("运行时验证任务 ID 不能为空");
        }
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("运行时验证应用 ID 必须大于 0");
        }
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("运行时验证用户 ID 必须大于 0");
        }
        Objects.requireNonNull(workspace, "运行时验证工作区不能为空");
        Objects.requireNonNull(workspace.codeGenType(), "运行时验证工作区类型不能为空");
        if (maximumDuration != null && maximumDuration.isNegative()) {
            throw new IllegalArgumentException("运行时验证剩余时间不能为负数");
        }
        cancellationRequested = cancellationRequested == null ? () -> false : cancellationRequested;
        onFrontendReady = onFrontendReady == null ? () -> { } : onFrontendReady;
    }
}
