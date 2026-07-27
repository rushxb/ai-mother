package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import java.time.Instant;
import java.util.List;

/** 经过签名和确定性门禁校验后持久化的不可变发布证据。 */
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
