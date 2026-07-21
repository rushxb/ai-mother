package com.rush.rushaicodemother.orchestration.benchmark;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GenerationBenchmarkService {

    private final GenerationBenchmarkRunner runner;
    private final OrchestratedGenerationBenchmarkExecutor orchestratedExecutor;
    private final GenerationBenchmarkReleaseGate releaseGate;

    public GenerationBenchmarkReport runEndToEndCatalog() {
        return runner.run(orchestratedExecutor);
    }

    public GenerationBenchmarkReleaseAssessment runReleaseGate() {
        return releaseGate.assess(runEndToEndCatalog());
    }
}
