package com.rush.rushaicodemother.orchestration.verification.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkBrowserProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeContext;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.benchmark.runtime.FullStackGenerationBenchmarkRuntimeGrader;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FullStackRuntimeProductionBenchmarkParityTest {

    @TempDir
    Path root;

    @Test
    void productionAndBenchmarkMustAgreeOnSameBrowserNetworkViolation() {
        BrowserRuntimeValidationResult browserFailure =
                BrowserRuntimeValidationResult.failed(10, "browser_network_error");
        DevServerValidationResult frontend = DevServerValidationResult
                .passed("fullstack-parity", 31L, 10)
                .withBrowserValidation(browserFailure);
        FullStackRuntimeValidationResult sharedObservation =
                new FullStackRuntimeValidationResult(
                        new BackendRuntimeValidationResult(
                                19_101,
                                true,
                                10,
                                "go run -mod=readonly ./cmd/server",
                                List.of()
                        ),
                        frontend,
                        20
                );
        ProjectRuntimeValidationResult production =
                ProjectRuntimeValidationResult.fromFullStack(sharedObservation);
        GeneratedFullStackRuntimeVerifier sharedVerifier =
                mock(GeneratedFullStackRuntimeVerifier.class);
        when(sharedVerifier.verify(any(Path.class), any(), any())).thenReturn(sharedObservation);
        FullStackGenerationBenchmarkRuntimeGrader benchmark =
                new FullStackGenerationBenchmarkRuntimeGrader(
                        new GenerationBenchmarkBackendProperties(),
                        new GenerationBenchmarkBrowserProperties(),
                        sharedVerifier
                );

        List<GenerationBenchmarkRuleResult> benchmarkResults =
                benchmark.evaluate(benchmarkContext());
        GenerationBenchmarkRuleResult browserRuntime = benchmarkResults.stream()
                .filter(result -> result.dimension()
                        == GenerationBenchmarkQualityDimension.RUNTIME)
                .filter(result -> result.violations().contains("browser_network_error"))
                .findFirst()
                .orElseThrow();

        assertFalse(production.passed());
        assertFalse(browserRuntime.passed());
        assertEquals("browser_network_error", production.failureKind());
    }

    private GenerationBenchmarkRuntimeContext benchmarkContext() {
        GenerationBenchmarkTask task = new GenerationBenchmarkTask(
                "fullstack-parity",
                "CREATE",
                CodeGenTypeEnum.FULL_STACK_PROJECT.getValue(),
                "生成全栈应用",
                "后端与浏览器可运行"
        );
        GenerationWorkspace workspace = new GenerationWorkspace(
                31L,
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                root,
                root,
                true,
                root.resolve("frontend"),
                root.resolve("backend"),
                Set.of(),
                Set.of()
        );
        return new GenerationBenchmarkRuntimeContext(task, workspace, 9L);
    }
}
