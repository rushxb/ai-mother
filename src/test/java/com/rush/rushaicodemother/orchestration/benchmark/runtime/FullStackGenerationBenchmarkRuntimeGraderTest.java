package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkBrowserProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeContext;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.verification.runtime.BackendRuntimeValidationResult;
import com.rush.rushaicodemother.orchestration.verification.runtime.FullStackRuntimeValidationResult;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedFullStackRuntimeVerifier;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationPolicy;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationRequest;
import com.rush.rushaicodemother.service.devserver.DevServerValidationResult;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FullStackGenerationBenchmarkRuntimeGraderTest {

    @TempDir
    Path root;

    @Test
    void sharedVerifierFactsMustMapToRuntimeAndVisualDimensions() {
        GeneratedFullStackRuntimeVerifier verifier =
                mock(GeneratedFullStackRuntimeVerifier.class);
        when(verifier.verify(any(Path.class), any(), any()))
                .thenReturn(successfulValidation());
        FullStackGenerationBenchmarkRuntimeGrader grader = grader(verifier);
        ArgumentCaptor<DevServerValidationRequest> request =
                ArgumentCaptor.forClass(DevServerValidationRequest.class);
        ArgumentCaptor<BrowserRuntimeValidationPolicy> policy =
                ArgumentCaptor.forClass(BrowserRuntimeValidationPolicy.class);

        List<GenerationBenchmarkRuleResult> results = grader.evaluate(context());

        assertTrue(results.stream().allMatch(GenerationBenchmarkRuleResult::passed));
        verify(verifier).verify(
                eq(context().workspace().backendRootPath()),
                request.capture(),
                policy.capture()
        );
        assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT, request.getValue().codeGenType());
        assertTrue(policy.getValue().requireVisualEvidence());
    }

    @Test
    void unhealthyBackendMustFailRuntimeAndSkipVisualDeterministically() {
        GeneratedFullStackRuntimeVerifier verifier =
                mock(GeneratedFullStackRuntimeVerifier.class);
        when(verifier.verify(any(Path.class), any(), any())).thenReturn(
                new FullStackRuntimeValidationResult(
                        BackendRuntimeValidationResult.failed(
                                10, "backend_health_json_invalid"),
                        null,
                        10
                )
        );
        FullStackGenerationBenchmarkRuntimeGrader grader = grader(verifier);

        List<GenerationBenchmarkRuleResult> results = grader.evaluate(context());

        assertTrue(results.stream().anyMatch(result ->
                result.dimension() == GenerationBenchmarkQualityDimension.RUNTIME
                        && result.violations().contains("backend_health_json_invalid")));
        assertTrue(results.stream().anyMatch(result ->
                result.dimension() == GenerationBenchmarkQualityDimension.VISUAL
                        && result.violations().contains(
                        "fullstack_visual_skipped_backend_unhealthy")));
    }

    @Test
    void browserNetworkAndVisualFailuresMustRemainSeparateEvidence() {
        GeneratedFullStackRuntimeVerifier verifier =
                mock(GeneratedFullStackRuntimeVerifier.class);
        BrowserRuntimeValidationResult browser = new BrowserRuntimeValidationResult(
                10,
                true,
                List.of("browser_network_error"),
                List.of("screenshot_near_uniform"),
                List.of(),
                Map.of()
        );
        DevServerValidationResult frontend = DevServerValidationResult
                .passed("fullstack-runtime", 12L, 10)
                .withBrowserValidation(browser);
        when(verifier.verify(any(Path.class), any(), any())).thenReturn(
                new FullStackRuntimeValidationResult(
                        healthyBackend(), frontend, 20)
        );
        FullStackGenerationBenchmarkRuntimeGrader grader = grader(verifier);

        List<GenerationBenchmarkRuleResult> results = grader.evaluate(context());

        assertTrue(results.stream().anyMatch(result ->
                result.dimension() == GenerationBenchmarkQualityDimension.RUNTIME
                        && result.violations().contains("browser_network_error")));
        assertTrue(results.stream().anyMatch(result ->
                result.dimension() == GenerationBenchmarkQualityDimension.VISUAL
                        && result.violations().contains("screenshot_near_uniform")));
        assertFalse(results.stream().allMatch(GenerationBenchmarkRuleResult::passed));
    }

    @Test
    void graderMustOnlySupportEnabledFullStackBenchmarks() {
        GeneratedFullStackRuntimeVerifier verifier =
                mock(GeneratedFullStackRuntimeVerifier.class);
        GenerationBenchmarkBackendProperties backendProperties =
                new GenerationBenchmarkBackendProperties();
        backendProperties.setEnabled(true);
        GenerationBenchmarkBrowserProperties browserProperties =
                new GenerationBenchmarkBrowserProperties();
        browserProperties.setEnabled(true);
        FullStackGenerationBenchmarkRuntimeGrader grader =
                new FullStackGenerationBenchmarkRuntimeGrader(
                        backendProperties, browserProperties, verifier);

        assertTrue(grader.supports(task(CodeGenTypeEnum.FULL_STACK_PROJECT)));
        assertFalse(grader.supports(task(CodeGenTypeEnum.VUE_PROJECT)));
        backendProperties.setEnabled(false);
        assertFalse(grader.supports(task(CodeGenTypeEnum.FULL_STACK_PROJECT)));
    }

    private FullStackGenerationBenchmarkRuntimeGrader grader(
            GeneratedFullStackRuntimeVerifier verifier
    ) {
        GenerationBenchmarkBackendProperties backendProperties =
                new GenerationBenchmarkBackendProperties();
        backendProperties.setEnabled(true);
        GenerationBenchmarkBrowserProperties browserProperties =
                new GenerationBenchmarkBrowserProperties();
        browserProperties.setEnabled(true);
        browserProperties.setSettleDelay(Duration.ofMillis(10));
        return new FullStackGenerationBenchmarkRuntimeGrader(
                backendProperties,
                browserProperties,
                verifier
        );
    }

    private FullStackRuntimeValidationResult successfulValidation() {
        BrowserRuntimeValidationResult browser = new BrowserRuntimeValidationResult(
                10, true, List.of(), List.of(), List.of(), Map.of());
        DevServerValidationResult frontend = DevServerValidationResult
                .passed("fullstack-runtime", 12L, 10)
                .withBrowserValidation(browser);
        return new FullStackRuntimeValidationResult(healthyBackend(), frontend, 20);
    }

    private BackendRuntimeValidationResult healthyBackend() {
        return new BackendRuntimeValidationResult(
                19_101,
                true,
                10,
                "go run -mod=readonly ./cmd/server",
                List.of()
        );
    }

    private GenerationBenchmarkRuntimeContext context() {
        return new GenerationBenchmarkRuntimeContext(
                task(CodeGenTypeEnum.FULL_STACK_PROJECT),
                workspace(),
                7L
        );
    }

    private GenerationBenchmarkTask task(CodeGenTypeEnum type) {
        return new GenerationBenchmarkTask(
                "fullstack-runtime",
                "CREATE",
                type.getValue(),
                "生成全栈应用",
                "前后端可运行"
        );
    }

    private GenerationWorkspace workspace() {
        Path frontend = root.resolve("frontend");
        Path backend = root.resolve("backend");
        return new GenerationWorkspace(
                12L,
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                root,
                root,
                true,
                frontend,
                backend,
                Set.of(),
                Set.of()
        );
    }
}
