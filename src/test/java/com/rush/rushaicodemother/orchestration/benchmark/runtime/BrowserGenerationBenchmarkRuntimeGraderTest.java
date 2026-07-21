package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBrowserProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeContext;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeObservation;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeProbe;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import com.rush.rushaicodemother.service.devserver.DevServerStartResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrowserGenerationBenchmarkRuntimeGraderTest {

    private GenerationBenchmarkBrowserProperties properties;
    private DevServerManager devServerManager;
    private BrowserRuntimeProbe browserRuntimeProbe;
    private BrowserGenerationBenchmarkRuntimeGrader grader;

    @BeforeEach
    void setUp() {
        properties = new GenerationBenchmarkBrowserProperties();
        properties.setEnabled(true);
        properties.setSettleDelay(Duration.ofMillis(10));
        devServerManager = mock(DevServerManager.class);
        browserRuntimeProbe = mock(BrowserRuntimeProbe.class);
        grader = new BrowserGenerationBenchmarkRuntimeGrader(
                properties,
                devServerManager,
                browserRuntimeProbe
        );
        when(devServerManager.startDevServer(any(), eq(9L)))
                .thenReturn(new DevServerStartResult(5_180, true));
    }

    @Test
    void healthyPreviewMustPassRuntimeAndVisualDimensionsAndCleanup() {
        when(browserRuntimeProbe.inspect(any(), eq(Duration.ofMillis(10))))
                .thenReturn(healthyObservation());

        List<GenerationBenchmarkRuleResult> results = grader.evaluate(context());

        assertTrue(passed(results, GenerationBenchmarkQualityDimension.RUNTIME));
        assertTrue(passed(results, GenerationBenchmarkQualityDimension.VISUAL));
        verify(devServerManager).stopDevServer(101L);
        verify(devServerManager).unregisterErrorCollector(eq(101L), any());
    }

    @Test
    void browserErrorsAndBlankScreenshotMustFailWithStableCodes() {
        BrowserRuntimeObservation unhealthy = new BrowserRuntimeObservation(
                URI.create("http://127.0.0.1:5180/"),
                URI.create("http://127.0.0.1:5180/"),
                "404 Page Not Found",
                "complete",
                0,
                1,
                true,
                0,
                1,
                1_600,
                900,
                true,
                "404 Page Not Found",
                "404",
                List.of(),
                List.of(),
                List.of(new BrowserRuntimeObservation.ConsoleMessage(
                        "SEVERE", "Uncaught TypeError: broken")),
                new BrowserRuntimeObservation.ScreenshotStats(true, 1_600, 900, 1, 0)
        );
        when(browserRuntimeProbe.inspect(any(), any())).thenReturn(unhealthy);

        List<GenerationBenchmarkRuleResult> results = grader.evaluate(context());

        GenerationBenchmarkRuleResult runtime = result(results, GenerationBenchmarkQualityDimension.RUNTIME);
        GenerationBenchmarkRuleResult visual = result(results, GenerationBenchmarkQualityDimension.VISUAL);
        assertFalse(runtime.passed());
        assertTrue(runtime.violations().contains("browser_console_error"));
        assertTrue(runtime.violations().contains("vite_error_overlay_present"));
        assertFalse(visual.passed());
        assertTrue(visual.violations().contains("screenshot_near_uniform"));
        assertTrue(visual.violations().contains("visual_error_state_rendered"));
    }

    @Test
    void probeFailureMustStillStopOwnedDevServer() {
        when(browserRuntimeProbe.inspect(any(), any()))
                .thenThrow(new IllegalStateException("browser failed"));

        assertThrows(IllegalStateException.class, () -> grader.evaluate(context()));

        verify(devServerManager).stopDevServer(101L);
        verify(devServerManager).unregisterErrorCollector(eq(101L), any());
    }

    @Test
    void onlyEnabledVueBenchmarksAreSupported() {
        assertTrue(grader.supports(task("vue_project")));
        assertFalse(grader.supports(task("full_stack_project")));
        assertFalse(grader.supports(task("backend_project")));

        properties.setEnabled(false);

        assertFalse(grader.supports(task("vue_project")));
    }

    private GenerationBenchmarkRuntimeContext context() {
        return new GenerationBenchmarkRuntimeContext(task("vue_project"), workspace(), 9L);
    }

    private GenerationBenchmarkTask task(String codeGenType) {
        return new GenerationBenchmarkTask(
                "browser", "CREATE", codeGenType, "build app", "build");
    }

    private GenerationWorkspace workspace() {
        Path root = Path.of("target", "browser-grader-test").toAbsolutePath().normalize();
        return new GenerationWorkspace(
                101L,
                CodeGenTypeEnum.VUE_PROJECT,
                root,
                root,
                true,
                root,
                null,
                Set.of(),
                Set.of()
        );
    }

    private BrowserRuntimeObservation healthyObservation() {
        URI uri = URI.create("http://127.0.0.1:5180/");
        return new BrowserRuntimeObservation(
                uri,
                uri,
                "Dashboard",
                "complete",
                120,
                1,
                true,
                1,
                12,
                1_600,
                900,
                false,
                "Dashboard content",
                "Dashboard",
                List.of("http://127.0.0.1:5180/src/main.ts"),
                List.of(),
                List.of(),
                new BrowserRuntimeObservation.ScreenshotStats(true, 1_600, 900, 12, 180)
        );
    }

    private boolean passed(
            List<GenerationBenchmarkRuleResult> results,
            GenerationBenchmarkQualityDimension dimension
    ) {
        return result(results, dimension).passed();
    }

    private GenerationBenchmarkRuleResult result(
            List<GenerationBenchmarkRuleResult> results,
            GenerationBenchmarkQualityDimension dimension
    ) {
        return results.stream()
                .filter(result -> result.dimension() == dimension)
                .findFirst()
                .orElseThrow();
    }
}
