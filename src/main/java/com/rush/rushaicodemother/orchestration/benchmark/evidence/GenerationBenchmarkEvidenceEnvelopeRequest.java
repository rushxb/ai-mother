package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;

import java.time.Duration;

/** Benchmark Worker 请求生成一份可直接提交的签名证据。 */
public record GenerationBenchmarkEvidenceEnvelopeRequest(
        GenerationBenchmarkEvidenceCandidate candidate,
        GenerationBenchmarkReport report,
        long candidatePhysicalRequestCount,
        Duration validity
) {
}
