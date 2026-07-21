package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;

/** Persisted benchmark fixture and its best-effort cleanup action. */
public record GenerationBenchmarkFixture(
        GenerationTaskRequest request,
        GenerationBenchmarkValidationPlan validationPlan,
        Runnable cleanup
) implements AutoCloseable {

    public GenerationBenchmarkFixture(GenerationTaskRequest request, Runnable cleanup) {
        this(request, GenerationBenchmarkValidationPlan.empty(), cleanup);
    }

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
