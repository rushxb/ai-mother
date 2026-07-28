package com.rush.rushaicodemother.infrastructure.sandbox;

import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/** 将生成代码命令转换为独立主机启动计划的策略端口。 */
public interface GeneratedCodeProcessSandbox {

    SandboxProcessPlan prepare(ManagedProcessRequest request, Path normalizedWorkingDirectory);

    default SandboxProcessPlan prepareDevServer(
            ManagedProcessRequest request,
            Path normalizedWorkingDirectory,
            int hostPort
    ) {
        return prepare(request, normalizedWorkingDirectory);
    }

    default void activate(SandboxProcessPlan plan) {
    }

    /** 在调用方剩余总时限和取消边界内激活沙箱附属资源。 */
    default void activate(
            SandboxProcessPlan plan,
            Duration remainingTimeout,
            BooleanSupplier cancellationRequested
    ) {
        activate(plan);
    }

    default void cleanup(SandboxProcessPlan plan) {
    }

    /** 在创建原始计划的进程消失后清理持久资源。 */
    default void cleanupResources(String backend, List<String> resourceIds) {
        if (resourceIds != null && !resourceIds.isEmpty()) {
            throw new UnsupportedOperationException(
                    "sandbox backend does not support durable resource recovery: " + backend);
        }
    }
}
