package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationBenchmarkReleaseGateTest {

    @Test
    void completeProductionEvidenceMustPassReleaseGate() {
        GenerationBenchmarkReleaseAssessment assessment = gate().assess(report(Map.of()));

        assertTrue(assessment.passed());
    }

    @Test
    void missingRuntimeVisualAndSecurityQualityMustBlockRelease() {
        Map<String, GenerationBenchmarkReport.QualityStats> overrides = Map.of(
                "security", quality(12, 11, 1.0, 11.0 / 12.0),
                "runtime", quality(8, 8, 8.0 / 12.0, 1.0),
                "visual", quality(9, 8, 0.75, 8.0 / 9.0)
        );

        GenerationBenchmarkReleaseAssessment assessment = gate().assess(report(overrides));

        assertFalse(assessment.passed());
        assertTrue(assessment.violations().contains("security_pass_rate_below_minimum"));
        assertTrue(assessment.violations().contains("runtime_evaluation_rate_below_minimum"));
        assertTrue(assessment.violations().contains("visual_pass_rate_below_minimum"));
    }

    private GenerationBenchmarkReleaseGate gate() {
        return new GenerationBenchmarkReleaseGate(new GenerationBenchmarkReleaseProperties());
    }

    private GenerationBenchmarkReport report(
            Map<String, GenerationBenchmarkReport.QualityStats> overrides
    ) {
        Map<String, GenerationBenchmarkReport.QualityStats> quality = new LinkedHashMap<>();
        quality.put("structural", quality(12, 12, 1.0, 1.0));
        quality.put("functional", quality(8, 8, 8.0 / 12.0, 1.0));
        quality.put("diff_scope", quality(7, 7, 7.0 / 12.0, 1.0));
        quality.put("security", quality(12, 12, 1.0, 1.0));
        quality.put("runtime", quality(9, 9, 0.75, 1.0));
        quality.put("visual", quality(9, 9, 0.75, 1.0));
        quality.putAll(overrides);
        return new GenerationBenchmarkReport(
                12,
                12,
                12,
                1.0,
                1.0,
                1_000,
                1_000,
                2_000,
                3_000,
                12,
                12,
                0,
                0,
                120_000,
                12,
                1_000,
                2_000,
                "prompt-bundle-1",
                quality,
                Map.of(),
                List.of()
        );
    }

    private GenerationBenchmarkReport.QualityStats quality(
            int evaluated,
            int passed,
            double evaluationRate,
            double passRate
    ) {
        return new GenerationBenchmarkReport.QualityStats(
                evaluated,
                passed,
                evaluationRate,
                passRate
        );
    }
}
