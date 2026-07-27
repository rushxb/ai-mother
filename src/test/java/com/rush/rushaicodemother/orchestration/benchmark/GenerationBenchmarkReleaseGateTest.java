package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.config.GenerationBenchmarkReleaseProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
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
                "security", quality(32, 31, 1.0, 31.0 / 32.0),
                "runtime", quality(17, 17, 17.0 / 18.0, 1.0),
                "visual", quality(18, 16, 1.0, 16.0 / 18.0)
        );

        GenerationBenchmarkReleaseAssessment assessment = gate().assess(report(overrides));

        assertFalse(assessment.passed());
        assertTrue(assessment.violations().contains("security_pass_rate_below_minimum"));
        assertTrue(assessment.violations().contains("runtime_evaluation_rate_below_minimum"));
        assertTrue(assessment.violations().contains("visual_pass_rate_below_minimum"));
    }

    @Test
    void missingFirstPreviewMustBlockReleaseWithoutBeingTreatedAsZeroLatency() {
        Map<String, GenerationBenchmarkReport.ModeStats> modes = new LinkedHashMap<>(modeStats());
        modes.put("CREATE", mode(8, 7, 0.875, 2_000));

        GenerationBenchmarkReleaseAssessment assessment = gate().assess(
                report(Map.of(), modes, 31, 31.0 / 32.0, 3_000));

        assertFalse(assessment.passed());
        assertTrue(assessment.violations().contains(
                "first_preview_observation_rate_below_minimum"));
        assertTrue(assessment.violations().contains(
                "create_first_preview_observation_rate_below_minimum"));
    }

    @ParameterizedTest
    @CsvSource({
            "CREATE,60001,create_p90_first_preview_latency_above_maximum",
            "LIGHT_EDIT,90001,light_edit_p90_first_preview_latency_above_maximum",
            "AGENT_EDIT,180001,agent_edit_p90_first_preview_latency_above_maximum",
            "HEAVY_EXPERT,300001,heavy_expert_p90_first_preview_latency_above_maximum"
    })
    void eachModeMustEnforceItsFirstPreviewSla(String mode,
                                               long latencyMs,
                                               String expectedViolation) {
        Map<String, GenerationBenchmarkReport.ModeStats> modes = new LinkedHashMap<>(modeStats());
        modes.put(mode, mode(8, 8, 1.0, latencyMs));

        GenerationBenchmarkReleaseAssessment assessment = gate().assess(
                report(Map.of(), modes, 32, 1.0, 3_000));

        assertFalse(assessment.passed());
        assertTrue(assessment.violations().contains(expectedViolation));
    }

    @Test
    void p99FirstPreviewTailMustBlockRelease() {
        GenerationBenchmarkReleaseAssessment assessment = gate().assess(
                report(Map.of(), modeStats(), 32, 1.0, 300_001));

        assertFalse(assessment.passed());
        assertTrue(assessment.violations().contains(
                "p99_first_preview_latency_above_maximum"));
    }

    @Test
    void p99FirstTokenTailMustBlockRelease() {
        GenerationBenchmarkReleaseProperties properties = new GenerationBenchmarkReleaseProperties();
        properties.setMaximumP99FirstTokenLatency(Duration.ofMillis(2_499));

        GenerationBenchmarkReleaseAssessment assessment =
                new GenerationBenchmarkReleaseGate(properties).assess(report(Map.of()));

        assertFalse(assessment.passed());
        assertTrue(assessment.violations().contains(
                "p99_first_token_latency_above_maximum"));
    }

    private GenerationBenchmarkReleaseGate gate() {
        return new GenerationBenchmarkReleaseGate(new GenerationBenchmarkReleaseProperties());
    }

    private GenerationBenchmarkReport report(
            Map<String, GenerationBenchmarkReport.QualityStats> overrides
    ) {
        return report(overrides, modeStats(), 32, 1.0, 3_000);
    }

    private GenerationBenchmarkReport report(
            Map<String, GenerationBenchmarkReport.QualityStats> overrides,
            Map<String, GenerationBenchmarkReport.ModeStats> modes,
            int firstPreviewObservedCount,
            double firstPreviewObservationRate,
            long p99FirstPreviewLatencyMs
    ) {
        Map<String, GenerationBenchmarkReport.QualityStats> quality = new LinkedHashMap<>();
        quality.put("structural", quality(32, 32, 1.0, 1.0));
        quality.put("functional", quality(20, 20, 1.0, 1.0));
        quality.put("diff_scope", quality(20, 20, 1.0, 1.0));
        quality.put("security", quality(32, 32, 1.0, 1.0));
        quality.put("runtime", quality(18, 18, 1.0, 1.0));
        quality.put("visual", quality(18, 18, 1.0, 1.0));
        quality.putAll(overrides);
        return new GenerationBenchmarkReport(
                GenerationBenchmarkReport.CURRENT_SCHEMA_VERSION,
                32,
                32,
                32,
                1.0,
                1.0,
                1_000,
                1_000,
                2_000,
                3_000,
                32,
                32,
                0,
                0,
                320_000,
                32,
                1_000,
                2_000,
                2_500,
                firstPreviewObservedCount,
                firstPreviewObservationRate,
                1_500,
                2_000,
                p99FirstPreviewLatencyMs,
                "prompt-bundle-1",
                "model-fingerprint-1",
                quality,
                modes,
                List.of()
        );
    }

    private Map<String, GenerationBenchmarkReport.ModeStats> modeStats() {
        Map<String, GenerationBenchmarkReport.ModeStats> modes = new LinkedHashMap<>();
        modes.put("CREATE", mode(8, 8, 1.0, 2_000));
        modes.put("LIGHT_EDIT", mode(8, 8, 1.0, 2_000));
        modes.put("AGENT_EDIT", mode(8, 8, 1.0, 2_000));
        modes.put("HEAVY_EXPERT", mode(8, 8, 1.0, 2_000));
        return modes;
    }

    private GenerationBenchmarkReport.ModeStats mode(int total,
                                                      int observed,
                                                      double observationRate,
                                                      long p90FirstPreviewLatencyMs) {
        return new GenerationBenchmarkReport.ModeStats(
                total,
                total,
                total,
                1.0,
                1.0,
                1_000,
                1_000,
                2_000,
                3_000,
                0,
                observed,
                observationRate,
                1_500,
                p90FirstPreviewLatencyMs,
                p90FirstPreviewLatencyMs
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
