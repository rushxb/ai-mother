package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;

import java.util.Objects;

/** 已完成时效、完整性、来源和发布门禁重放的 Benchmark 证据。 */
public record GenerationVerifiedBenchmarkEvidence(
        GenerationBenchmarkEvidenceRecord evidence,
        GenerationBenchmarkReport report
) {

    public GenerationVerifiedBenchmarkEvidence {
        Objects.requireNonNull(evidence, "Benchmark 证据不能为空");
        Objects.requireNonNull(report, "Benchmark 报告不能为空");
    }
}
