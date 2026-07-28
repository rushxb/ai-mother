package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;

/** 坚持基准装置及其尽最大努力的清理行动。 */
public record GenerationBenchmarkFixture(
        GenerationTaskRequest request,
        GenerationBenchmarkValidationPlan validationPlan,
        Runnable cleanup
) implements AutoCloseable {

    public GenerationBenchmarkFixture(GenerationTaskRequest request, Runnable cleanup) {
        this(request, GenerationBenchmarkValidationPlan.empty(), cleanup);
    }

    /** 创建生成基准测试{@code Fixture}实例并完成必要的依赖和初始状态设置。 */
    public GenerationBenchmarkFixture {
        if (request == null) {
            throw new IllegalArgumentException("benchmark request cannot be null");
        }
        validationPlan = validationPlan == null
                ? GenerationBenchmarkValidationPlan.empty()
                : validationPlan;
        cleanup = cleanup == null ? () -> { } : cleanup;
    }

    @Override
    public void close() {
        cleanup.run();
    }
}
