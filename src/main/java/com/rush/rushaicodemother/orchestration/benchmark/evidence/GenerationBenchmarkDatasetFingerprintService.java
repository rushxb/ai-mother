package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkCatalog;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Content-addresses the exact benchmark dataset rather than relying on a mutable display name. */
@Component
@RequiredArgsConstructor
public class GenerationBenchmarkDatasetFingerprintService {

    private final GenerationBenchmarkCatalog catalog;

    public String currentFingerprint() {
        StringBuilder canonical = new StringBuilder("generation-benchmark-dataset-v1|");
        for (GenerationBenchmarkTask task : catalog.tasks()) {
            ReleaseCandidateFingerprint.appendField(canonical, task.id());
            ReleaseCandidateFingerprint.appendField(canonical, task.mode());
            ReleaseCandidateFingerprint.appendField(canonical, task.codeGenType());
            ReleaseCandidateFingerprint.appendField(canonical, task.prompt());
            ReleaseCandidateFingerprint.appendField(canonical, task.expectedValidation());
        }
        return ReleaseCandidateFingerprint.sha256(canonical.toString());
    }
}
