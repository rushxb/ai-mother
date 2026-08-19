package com.rush.rushaicodemother.orchestration.verification.runtime;

import java.nio.file.Path;

/** 生成后端托管运行时 seam；生产验证与 Benchmark 共同消费同一 adapter。 */
public interface GeneratedBackendRuntime {

    /** 启动后端运行时，并在启动、探测和持有窗口内持续观察调用方取消状态。 */
    GeneratedBackendRuntimeHandle start(GeneratedBackendRuntimeRequest request);

    /** Benchmark 等非任务调用方使用无取消约束的兼容入口。 */
    default GeneratedBackendRuntimeHandle start(Path backendProjectDirectory) {
        return start(GeneratedBackendRuntimeRequest.unmanaged(backendProjectDirectory));
    }
}
