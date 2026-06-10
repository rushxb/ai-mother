package com.rush.rushaicodemother.orchestration.benchmark;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GenerationBenchmarkService {

    private final GenerationBenchmarkRunner runner;
    private final OrchestratedGenerationBenchmarkExecutor orchestratedExecutor;

    public GenerationBenchmarkReport runEndToEndCatalog() {
        return runner.run(orchestratedExecutor);
    }
}
