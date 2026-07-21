package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import java.util.Optional;

public interface GenerationBenchmarkEvidenceRepository {

    void insert(GenerationBenchmarkEvidenceRecord evidence);

    Optional<GenerationBenchmarkEvidenceRecord> findByEvidenceId(String evidenceId);
}
