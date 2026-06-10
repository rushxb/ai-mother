package com.rush.rushaicodemother.orchestration.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationBenchmarkCatalogTest {

    @Test
    void shouldExposeCreateAndEditBenchmarkTasks() {
        GenerationBenchmarkCatalog catalog = new GenerationBenchmarkCatalog();

        assertTrue(catalog.tasks().stream().anyMatch(task -> "CREATE".equals(task.mode())));
        assertTrue(catalog.tasks().stream().anyMatch(task -> "LIGHT_EDIT".equals(task.mode())));
        assertTrue(catalog.tasks().stream().anyMatch(task -> "AGENT_EDIT".equals(task.mode())));
        assertTrue(catalog.tasks().size() >= 12);
    }

    @Test
    void shouldSummarizeBenchmarkResultsWithModeStatsAndPercentiles() {
        GenerationBenchmarkRunner runner = new GenerationBenchmarkRunner(new GenerationBenchmarkCatalog());
        List<GenerationBenchmarkRunResult> results = List.of(
                new GenerationBenchmarkRunResult("create-1", "CREATE", true, true, 100, 2, 0, false, 0, ""),
                new GenerationBenchmarkRunResult("create-2", "CREATE", false, false, 300, 2, 0, true, 1, "build_failed"),
                new GenerationBenchmarkRunResult("edit-1", "AGENT_EDIT", true, true, 200, 1, 3, false, 1, "")
        );

        GenerationBenchmarkReport report = runner.summarize(results);

        assertEquals(3, report.totalTasks());
        assertEquals(2, report.successCount());
        assertEquals(2.0 / 3.0, report.successRate());
        assertEquals(200, report.averageDurationMs());
        assertEquals(200, report.p50DurationMs());
        assertEquals(300, report.p90DurationMs());
        assertEquals(1, report.fallbackCount());
        assertEquals(5, report.aiCallCount());
        assertEquals(3, report.toolCallCount());
        assertEquals(2, report.repairRounds());
        assertEquals(2, report.modeStats().get("CREATE").totalTasks());
        assertEquals(0.5, report.modeStats().get("CREATE").successRate());
    }
}
