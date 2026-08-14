package com.rush.rushaicodemother.orchestration.verification.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeContext;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.benchmark.runtime.BackendGenerationBenchmarkRuntimeGrader;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackendRuntimeProductionBenchmarkParityTest {

    @TempDir
    Path backendProject;

    @Test
    void productionAndBenchmarkMustAgreeOnSameBackendRuntimeViolation() {
        GeneratedBackendRuntime sharedRuntime =
                mock(GeneratedBackendRuntime.class);
        GeneratedBackendRuntimeObservation observation =
                GeneratedBackendRuntimeObservation.failed("backend_health_json_invalid");
        when(sharedRuntime.start(backendProject)).thenReturn(
                GeneratedBackendRuntimeHandle.failed(observation),
                GeneratedBackendRuntimeHandle.failed(observation));
        GeneratedBackendRuntimeVerifier productionVerifier =
                new GeneratedBackendRuntimeVerifier(sharedRuntime);
        BackendGenerationBenchmarkRuntimeGrader benchmarkGrader =
                new BackendGenerationBenchmarkRuntimeGrader(
                        new GenerationBenchmarkBackendProperties(), sharedRuntime);

        BackendRuntimeValidationResult productionResult =
                productionVerifier.verify(backendProject);
        List<GenerationBenchmarkRuleResult> benchmarkResults =
                benchmarkGrader.evaluate(benchmarkContext());

        assertFalse(productionResult.passed());
        assertFalse(benchmarkResults.getFirst().passed());
        assertEquals(
                productionResult.violations(),
                benchmarkResults.getFirst().violations());
    }

    private GenerationBenchmarkRuntimeContext benchmarkContext() {
        GenerationBenchmarkTask task = new GenerationBenchmarkTask(
                "backend-runtime-parity",
                "CREATE",
                CodeGenTypeEnum.BACKEND_PROJECT.getValue(),
                "生成后端",
                "运行时健康");
        GenerationWorkspace workspace = new GenerationWorkspace(
                21L,
                CodeGenTypeEnum.BACKEND_PROJECT,
                backendProject,
                backendProject,
                true,
                backendProject,
                backendProject,
                Set.of(),
                Set.of());
        return new GenerationBenchmarkRuntimeContext(task, workspace, 9L);
    }
}
