package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuleResult;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkRuntimeContext;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackendGenerationBenchmarkRuntimeGraderTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void backendTaskMustMapProbeViolationsAndAlwaysCloseRuntimeHandle() {
        GenerationBenchmarkBackendProperties properties = enabledProperties();
        GenerationBenchmarkBackendRuntime runtime = mock(GenerationBenchmarkBackendRuntime.class);
        AtomicBoolean closed = new AtomicBoolean(false);
        when(runtime.start(workspaceRoot)).thenReturn(new BackendRuntimeHandle(
                19_001,
                BackendRuntimeObservation.failed("backend_health_json_invalid"),
                () -> closed.set(true)
        ));
        BackendGenerationBenchmarkRuntimeGrader grader =
                new BackendGenerationBenchmarkRuntimeGrader(properties, runtime);

        List<GenerationBenchmarkRuleResult> results = grader.evaluate(context());

        assertFalse(results.getFirst().passed());
        assertTrue(results.getFirst().violations().contains("backend_health_json_invalid"));
        assertTrue(closed.get());
    }

    @Test
    void graderMustOnlySupportEnabledBackendTasks() {
        GenerationBenchmarkBackendProperties properties = enabledProperties();
        BackendGenerationBenchmarkRuntimeGrader grader =
                new BackendGenerationBenchmarkRuntimeGrader(
                        properties,
                        mock(GenerationBenchmarkBackendRuntime.class)
                );

        assertTrue(grader.supports(task(CodeGenTypeEnum.BACKEND_PROJECT)));
        assertFalse(grader.supports(task(CodeGenTypeEnum.VUE_PROJECT)));
        properties.setEnabled(false);
        assertFalse(grader.supports(task(CodeGenTypeEnum.BACKEND_PROJECT)));
        assertTrue(grader.dimensions().contains(GenerationBenchmarkQualityDimension.RUNTIME));
    }

    private GenerationBenchmarkBackendProperties enabledProperties() {
        GenerationBenchmarkBackendProperties properties =
                new GenerationBenchmarkBackendProperties();
        properties.setEnabled(true);
        return properties;
    }

    private GenerationBenchmarkRuntimeContext context() {
        return new GenerationBenchmarkRuntimeContext(
                task(CodeGenTypeEnum.BACKEND_PROJECT),
                new GenerationWorkspace(
                        11L,
                        CodeGenTypeEnum.BACKEND_PROJECT,
                        workspaceRoot,
                        workspaceRoot,
                        true,
                        workspaceRoot,
                        workspaceRoot,
                        Set.of(),
                        Set.of()
                ),
                7L
        );
    }

    private GenerationBenchmarkTask task(CodeGenTypeEnum type) {
        return new GenerationBenchmarkTask(
                "backend-runtime",
                "CREATE",
                type.getValue(),
                "生成后端",
                "运行时健康"
        );
    }
}
