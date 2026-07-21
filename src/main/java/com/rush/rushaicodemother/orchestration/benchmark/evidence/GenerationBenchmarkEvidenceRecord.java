package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import java.time.Instant;
import java.util.List;

/** Immutable release evidence persisted after signature and deterministic gate verification. */
public record GenerationBenchmarkEvidenceRecord(
        String evidenceId,
        GenerationBenchmarkEvidencePayload payload,
        String reportJson,
        boolean passed,
        List<String> violations,
        String signature,
        Instant createdAt
) {
    public GenerationBenchmarkEvidenceRecord {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }
}
