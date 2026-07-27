package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkBrowserProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeContext;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.service.devserver.DevServerStartOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FullStackGenerationBenchmarkRuntimeGraderTest {

    @TempDir
    Path root;

    @Test
    void healthyBackendMustRemainOwnedWhileBrowserUsesInjectedApiBase() {
        GenerationBenchmarkBackendRuntime backendRuntime = mock(GenerationBenchmarkBackendRuntime.class);
        BrowserGenerationRuntimeEvaluator browserEvaluator = mock(BrowserGenerationRuntimeEvaluator.class);
        AtomicBoolean closed = new AtomicBoolean(false);
        BackendRuntimeHandle handle = new BackendRuntimeHandle(
                19_101,
                BackendRuntimeObservation.passed(),
                () -> true,
                () -> closed.set(true)
        );
        when(backendRuntime.start(context().workspace().backendRootPath())).thenReturn(handle);
        when(browserEvaluator.evaluate(any(), eq(CodeGenTypeEnum.FULL_STACK_PROJECT), any()))
                .thenReturn(List.of(
                        GenerationBenchmarkRuleResult.passed(
                                "browser_runtime",
                                GenerationBenchmarkQualityDimension.RUNTIME
                        ),
                        GenerationBenchmarkRuleResult.passed(
                                "browser_visual",
                                GenerationBenchmarkQualityDimension.VISUAL
                        )
                ));
        FullStackGenerationBenchmarkRuntimeGrader grader = grader(backendRuntime, browserEvaluator);
        ArgumentCaptor<DevServerStartOptions> optionsCaptor =
                ArgumentCaptor.forClass(DevServerStartOptions.class);

        List<GenerationBenchmarkRuleResult> results = grader.evaluate(context());

        verify(browserEvaluator).evaluate(
                any(),
                eq(CodeGenTypeEnum.FULL_STACK_PROJECT),
                optionsCaptor.capture()
        );
        assertEquals(
                "http://127.0.0.1:19101/api",
                optionsCaptor.getValue().environmentOverrides().get("VITE_API_BASE_URL")
        );
        assertTrue(results.stream().allMatch(GenerationBenchmarkRuleResult::passed));
        assertTrue(closed.get());
    }

    @Test
    void unhealthyBackendMustFailRuntimeAndSkipBrowserDeterministically() {
        GenerationBenchmarkBackendRuntime backendRuntime = mock(GenerationBenchmarkBackendRuntime.class);
        BrowserGenerationRuntimeEvaluator browserEvaluator = mock(BrowserGenerationRuntimeEvaluator.class);
        when(backendRuntime.start(context().workspace().backendRootPath())).thenReturn(
                BackendRuntimeHandle.failed(
                        BackendRuntimeObservation.failed("backend_health_json_invalid")
                )
        );
        FullStackGenerationBenchmarkRuntimeGrader grader = grader(backendRuntime, browserEvaluator);

        List<GenerationBenchmarkRuleResult> results = grader.evaluate(context());

        assertTrue(results.stream().anyMatch(result ->
                result.dimension() == GenerationBenchmarkQualityDimension.RUNTIME
                        && result.violations().contains("backend_health_json_invalid")));
        assertTrue(results.stream().anyMatch(result ->
                result.dimension() == GenerationBenchmarkQualityDimension.VISUAL
                        && result.violations().contains(
                        "fullstack_visual_skipped_backend_unhealthy")));
        verify(browserEvaluator, never()).evaluate(any(), any(), any());
    }

    @Test
    void backendExitDuringBrowserWindowMustFailRuntimeDimension() {
        GenerationBenchmarkBackendRuntime backendRuntime = mock(GenerationBenchmarkBackendRuntime.class);
        BrowserGenerationRuntimeEvaluator browserEvaluator = mock(BrowserGenerationRuntimeEvaluator.class);
        AtomicBoolean alive = new AtomicBoolean(true);
        when(backendRuntime.start(context().workspace().backendRootPath())).thenReturn(
                new BackendRuntimeHandle(
                        19_101,
                        BackendRuntimeObservation.passed(),
                        alive::get,
                        () -> { }
                )
        );
        when(browserEvaluator.evaluate(any(), eq(CodeGenTypeEnum.FULL_STACK_PROJECT), any()))
                .thenAnswer(invocation -> {
                    alive.set(false);
                    return List.of(
                            GenerationBenchmarkRuleResult.passed(
                                    "browser_runtime",
                                    GenerationBenchmarkQualityDimension.RUNTIME
                            ),
                            GenerationBenchmarkRuleResult.passed(
                                    "browser_visual",
                                    GenerationBenchmarkQualityDimension.VISUAL
                            )
                    );
                });
        FullStackGenerationBenchmarkRuntimeGrader grader = grader(backendRuntime, browserEvaluator);

        List<GenerationBenchmarkRuleResult> results = grader.evaluate(context());

        assertTrue(results.stream().anyMatch(result -> result.violations().contains(
                "backend_process_exited_during_browser")));
        assertFalse(results.stream()
                .filter(result -> result.dimension() == GenerationBenchmarkQualityDimension.RUNTIME)
                .allMatch(GenerationBenchmarkRuleResult::passed));
    }

    private FullStackGenerationBenchmarkRuntimeGrader grader(
            GenerationBenchmarkBackendRuntime backendRuntime,
            BrowserGenerationRuntimeEvaluator browserEvaluator
    ) {
        GenerationBenchmarkBackendProperties backendProperties =
                new GenerationBenchmarkBackendProperties();
        backendProperties.setEnabled(true);
        GenerationBenchmarkBrowserProperties browserProperties =
                new GenerationBenchmarkBrowserProperties();
        browserProperties.setEnabled(true);
        return new FullStackGenerationBenchmarkRuntimeGrader(
                backendProperties,
                browserProperties,
                new DevServerRuntimeProperties(),
                backendRuntime,
                browserEvaluator
        );
    }

    private GenerationBenchmarkRuntimeContext context() {
        Path frontend = root.resolve("frontend");
        Path backend = root.resolve("backend");
        return new GenerationBenchmarkRuntimeContext(
                new GenerationBenchmarkTask(
                        "fullstack-runtime",
                        "CREATE",
                        CodeGenTypeEnum.FULL_STACK_PROJECT.getValue(),
                        "生成全栈应用",
                        "前后端可运行"
                ),
                new GenerationWorkspace(
                        12L,
                        CodeGenTypeEnum.FULL_STACK_PROJECT,
                        root,
                        root,
                        true,
                        frontend,
                        backend,
                        Set.of(),
                        Set.of()
                ),
                7L
        );
    }
}
