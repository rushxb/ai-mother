package com.rush.rushaicodemother.orchestration.benchmark;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 生成基准测试服务实现。
 */
@Service
@RequiredArgsConstructor
public class GenerationBenchmarkService {

    private final GenerationBenchmarkRunner runner;
    private final OrchestratedGenerationBenchmarkExecutor orchestratedExecutor;
    private final GenerationBenchmarkReportValidator reportValidator;
    private final GenerationBenchmarkReleaseGate releaseGate;

    public GenerationBenchmarkReport runEndToEndCatalog() {
        return runner.run(orchestratedExecutor);
    }

    public GenerationBenchmarkReleaseAssessment runReleaseGate() {
        GenerationBenchmarkReport report = runEndToEndCatalog();
        reportValidator.validate(report);
        return releaseGate.assess(report);
    }
}
