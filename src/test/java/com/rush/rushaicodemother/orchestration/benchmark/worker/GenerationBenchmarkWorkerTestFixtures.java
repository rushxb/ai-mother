package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkReport;

import java.util.List;
import java.util.Map;

final class GenerationBenchmarkWorkerTestFixtures {

    private GenerationBenchmarkWorkerTestFixtures() {
    }

    static GenerationBenchmarkReport report(String modelFingerprint,
                                            String promptBundleFingerprint) {
        return new GenerationBenchmarkReport(
                GenerationBenchmarkReport.CURRENT_SCHEMA_VERSION,
                1,
                1,
                1,
                1.0,
                1.0,
                10,
                10,
                10,
                10,
                1,
                0,
                0,
                0,
                100,
                1,
                5,
                5,
                5,
                1,
                1.0,
                10,
                10,
                10,
                promptBundleFingerprint,
                modelFingerprint,
                Map.of(),
                Map.of(),
                List.of()
        );
    }
}
