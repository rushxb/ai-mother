package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GenerationBenchmarkGraderMetricsCollectorTest {

    @Test
    void metricsMustUseBoundedOperationalTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GenerationBenchmarkGraderMetricsCollector collector =
                new GenerationBenchmarkGraderMetricsCollector(registry);

        collector.record(
                "runtime",
                GenerationBenchmarkQualityDimension.VISUAL,
                "passed",
                Duration.ofMillis(25)
        );

        assertEquals(1.0, registry.get("ai_generation_benchmark_grader_results_total")
                .tag("kind", "runtime")
                .tag("dimension", "visual")
                .tag("status", "passed")
                .counter()
                .count());
        assertNotNull(registry.get("ai_generation_benchmark_grader_duration_seconds")
                .tag("kind", "runtime")
                .tag("dimension", "visual")
                .tag("status", "passed")
                .timer());
    }
}
