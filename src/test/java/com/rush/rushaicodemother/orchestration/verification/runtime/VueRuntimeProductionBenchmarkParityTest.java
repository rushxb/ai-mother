package com.rush.rushaicodemother.orchestration.verification.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBrowserProperties;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeContext;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.benchmark.runtime.BrowserGenerationBenchmarkRuntimeGrader;
import com.rush.rushaicodemother.orchestration.benchmark.runtime.BrowserGenerationRuntimeEvaluator;
import com.rush.rushaicodemother.orchestration.heavy.GenerationProjectRuntimeValidationRequest;
import com.rush.rushaicodemother.orchestration.heavy.VueProjectValidationAdapter;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationRequest;
import com.rush.rushaicodemother.service.devserver.DevServerValidationResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VueRuntimeProductionBenchmarkParityTest {

    @TempDir
    Path root;

    @Test
    void productionAndBenchmarkMustUseTheSameVerifierWithExplicitPolicies() {
        DevServerValidationService sharedVerifier = mock(DevServerValidationService.class);
        BrowserRuntimeValidationResult browserFailure = new BrowserRuntimeValidationResult(
                10,
                true,
                List.of("browser_network_error"),
                List.of("screenshot_near_uniform"),
                List.of(),
                Map.of()
        );
        DevServerValidationResult sharedObservation = DevServerValidationResult
                .passed("vue-runtime", 41L, 10)
                .withBrowserValidation(browserFailure);
        when(sharedVerifier.validate(any(DevServerValidationRequest.class)))
                .thenReturn(sharedObservation, sharedObservation);

        VueProjectValidationAdapter production = new VueProjectValidationAdapter(
                mock(VueProjectBuilder.class), sharedVerifier);
        ProjectRuntimeValidationResult productionResult = production.validateRuntime(
                new GenerationProjectRuntimeValidationRequest(
                        "vue-runtime", 41L, 9L, workspace(), null, null,
                        () -> false, () -> { }
                ));

        GenerationBenchmarkBrowserProperties properties = new GenerationBenchmarkBrowserProperties();
        properties.setEnabled(true);
        properties.setSettleDelay(Duration.ofMillis(10));
        BrowserGenerationBenchmarkRuntimeGrader benchmark =
                new BrowserGenerationBenchmarkRuntimeGrader(
                        properties,
                        new BrowserGenerationRuntimeEvaluator(properties, sharedVerifier)
                );
        List<GenerationBenchmarkRuleResult> benchmarkResults = benchmark.evaluate(
                new GenerationBenchmarkRuntimeContext(task(), workspace(), 9L));

        assertFalse(productionResult.passed());
        assertEquals("BROWSER_RUNTIME_ERROR", productionResult.failureKind());
        @SuppressWarnings("unchecked")
        Map<String, Object> productionBrowser = (Map<String, Object>)
                productionResult.eventData().get("browserValidation");
        assertTrue(((List<?>) productionBrowser.get("runtimeViolations"))
                .contains("browser_network_error"));
        assertTrue(benchmarkResults.stream().anyMatch(result ->
                result.dimension() == GenerationBenchmarkQualityDimension.RUNTIME
                        && result.violations().contains("browser_network_error")));

        ArgumentCaptor<DevServerValidationRequest> requests =
                ArgumentCaptor.forClass(DevServerValidationRequest.class);
        verify(sharedVerifier, org.mockito.Mockito.times(2)).validate(requests.capture());
        assertFalse(requests.getAllValues().get(0)
                .browserValidationPolicy().requireVisualEvidence());
        assertTrue(requests.getAllValues().get(1)
                .browserValidationPolicy().requireVisualEvidence());
    }

    private GenerationBenchmarkTask task() {
        return new GenerationBenchmarkTask(
                "vue-runtime", "CREATE", CodeGenTypeEnum.VUE_PROJECT.getValue(),
                "生成 Vue 应用", "页面可运行");
    }

    private GenerationWorkspace workspace() {
        return new GenerationWorkspace(
                41L,
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
}
