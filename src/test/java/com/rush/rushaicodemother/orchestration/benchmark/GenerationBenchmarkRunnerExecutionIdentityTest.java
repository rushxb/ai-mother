package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkModelFingerprintProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationBenchmarkRunnerExecutionIdentityTest {

    private static final String PROMPT_A = "a".repeat(64);
    private static final String PROMPT_B = "b".repeat(64);
    private static final String MODEL_A = "c".repeat(64);
    private static final String MODEL_B = "d".repeat(64);

    private GenerationBenchmarkCatalog catalog;
    private PromptCatalog promptCatalog;
    private GenerationBenchmarkModelFingerprintProvider modelFingerprintProvider;

    @BeforeEach
    void setUp() {
        catalog = mock(GenerationBenchmarkCatalog.class);
        promptCatalog = mock(PromptCatalog.class);
        modelFingerprintProvider = mock(GenerationBenchmarkModelFingerprintProvider.class);
        when(catalog.tasks()).thenReturn(List.of(task()));
    }

    @Test
    void runMustRecordTheIdentityUsedByTheWholeExecution() {
        when(promptCatalog.bundleId()).thenReturn(PROMPT_A);
        when(modelFingerprintProvider.currentFingerprint()).thenReturn(MODEL_A);

        GenerationBenchmarkReport report = runner().run(ignored -> result());

        assertEquals(PROMPT_A, report.promptBundleId());
        assertEquals(MODEL_A, report.modelFingerprint());
    }

    @Test
    void runMustRejectPromptDriftDuringExecution() {
        when(promptCatalog.bundleId()).thenReturn(PROMPT_A, PROMPT_B);
        when(modelFingerprintProvider.currentFingerprint()).thenReturn(MODEL_A);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> runner().run(ignored -> result())
        );

        assertEquals("Benchmark 执行期间 Prompt 或模型配置发生变化，已拒绝生成报告",
                failure.getMessage());
    }

    @Test
    void runMustRejectModelDriftDuringExecution() {
        when(promptCatalog.bundleId()).thenReturn(PROMPT_A);
        when(modelFingerprintProvider.currentFingerprint()).thenReturn(MODEL_A, MODEL_B);

        assertThrows(IllegalStateException.class, () -> runner().run(ignored -> result()));
    }

    private GenerationBenchmarkRunner runner() {
        return new GenerationBenchmarkRunner(catalog, promptCatalog, modelFingerprintProvider);
    }

    private GenerationBenchmarkTask task() {
        return new GenerationBenchmarkTask(
                "task-1", "CREATE", "vue_project", "生成页面", "fast");
    }

    private GenerationBenchmarkRunResult result() {
        return new GenerationBenchmarkRunResult(
                "task-1", "CREATE", true, true, 10, 1, 0, false, 0, "");
    }
}
