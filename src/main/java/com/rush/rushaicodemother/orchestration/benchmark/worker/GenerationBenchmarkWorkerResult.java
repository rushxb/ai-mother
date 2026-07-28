package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceProtocol;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;

import java.util.List;

/** Benchmark Worker 写给 CI 的稳定结果协议。 */
public record GenerationBenchmarkWorkerResult(
        int schemaVersion,
        Status status,
        GenerationBenchmarkEvidenceSubject subjectType,
        String subjectKey,
        String candidateFingerprint,
        long candidatePhysicalRequestCount,
        String evidenceId,
        List<String> violations,
        GenerationBenchmarkReport report
) {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    /** 创建生成基准测试工作器结果实例并完成必要的依赖和初始状态设置。 */
    public GenerationBenchmarkWorkerResult {
        subjectKey = subjectKey == null ? "" : subjectKey;
        candidateFingerprint = candidateFingerprint == null ? "" : candidateFingerprint;
        evidenceId = evidenceId == null ? "" : evidenceId;
        violations = violations == null ? List.of() : List.copyOf(violations);
        if (schemaVersion != CURRENT_SCHEMA_VERSION || status == null
                || subjectType == null || report == null
                || !GenerationBenchmarkEvidenceProtocol.hasCurrentAttestation(
                GenerationBenchmarkEvidenceProtocol.CURRENT_SIGNATURE_VERSION,
                subjectType,
                candidatePhysicalRequestCount)) {
            throw new IllegalArgumentException("Benchmark Worker 结果不完整");
        }
    }

    public enum Status {
        PASSED,
        REJECTED
    }
}
