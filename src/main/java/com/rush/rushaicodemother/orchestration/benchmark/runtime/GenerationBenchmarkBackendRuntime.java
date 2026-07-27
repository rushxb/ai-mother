package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import java.nio.file.Path;

/** 在隔离副本中启动并持有一个生成后端，供单体与全栈评分复用。 */
public interface GenerationBenchmarkBackendRuntime {

    BackendRuntimeHandle start(Path backendProjectDirectory);
}
