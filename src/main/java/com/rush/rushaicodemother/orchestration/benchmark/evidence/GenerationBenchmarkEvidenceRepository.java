package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import java.util.Optional;

/**
 * 生成基准测试证据持久化仓储。
 */
public interface GenerationBenchmarkEvidenceRepository {

    void insert(GenerationBenchmarkEvidenceRecord evidence);

    Optional<GenerationBenchmarkEvidenceRecord> findByEvidenceId(String evidenceId);
}
