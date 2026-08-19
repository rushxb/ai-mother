package com.rush.rushaicodemother.orchestration.verification.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 生成后端运行时的稳定执行契约。
 *
 * <p>任务调用方同时提供剩余时间和取消探针；Benchmark 等非托管调用方可以省略任务时间，
 * 但仍受 runtime 自身配置上限约束。低层进程无需理解任务、租约或执行栅栏。</p>
 */
public record GeneratedBackendRuntimeRequest(
        Path projectDirectory,
        Duration maximumDuration,
        BooleanSupplier cancellationRequested
) {

    public GeneratedBackendRuntimeRequest {
        Objects.requireNonNull(projectDirectory, "后端运行时工程目录不能为空");
        if (maximumDuration != null && maximumDuration.isNegative()) {
            throw new IllegalArgumentException("后端运行时时间预算不能为负数");
        }
        cancellationRequested = cancellationRequested == null ? () -> false : cancellationRequested;
    }

    /** 创建只受 runtime 固有配置约束的非托管请求。 */
    public static GeneratedBackendRuntimeRequest unmanaged(Path projectDirectory) {
        return new GeneratedBackendRuntimeRequest(projectDirectory, null, () -> false);
    }

    /** 将 runtime 固有超时夹紧到任务剩余窗口。 */
    public Duration clamp(Duration configuredDuration) {
        Objects.requireNonNull(configuredDuration, "后端运行时配置时长不能为空");
        if (configuredDuration.isZero() || configuredDuration.isNegative()) {
            throw new IllegalArgumentException("后端运行时配置时长必须大于 0");
        }
        if (maximumDuration == null || configuredDuration.compareTo(maximumDuration) <= 0) {
            return configuredDuration;
        }
        return maximumDuration;
    }

    public boolean isCancellationRequested() {
        return cancellationRequested.getAsBoolean();
    }
}
