package com.rush.rushaicodemother.orchestration.verification.runtime;

import java.nio.file.Path;

/** 生成后端托管运行时 seam；生产验证与 Benchmark 共同消费同一 adapter。 */
public interface GeneratedBackendRuntime {

    GeneratedBackendRuntimeHandle start(Path backendProjectDirectory);
}
