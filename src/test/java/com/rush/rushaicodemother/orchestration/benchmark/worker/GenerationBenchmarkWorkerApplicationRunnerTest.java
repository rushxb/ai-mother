package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationBenchmarkWorkerApplicationRunnerTest {

    @Test
    void successMustExposeZeroExitCode() {
        GenerationBenchmarkWorkerExecutionService service =
                mock(GenerationBenchmarkWorkerExecutionService.class);
        when(service.execute()).thenReturn(result("evidence-1"));
        GenerationBenchmarkWorkerApplicationRunner runner =
                new GenerationBenchmarkWorkerApplicationRunner(service);

        runner.run(mock(ApplicationArguments.class));

        assertEquals(GenerationBenchmarkWorkerApplicationRunner.EXIT_SUCCESS,
                runner.getExitCode());
    }

    @Test
    void gateRejectionMustExposeDedicatedExitCode() {
        GenerationBenchmarkWorkerExecutionService service =
                mock(GenerationBenchmarkWorkerExecutionService.class);
        when(service.execute()).thenThrow(
                new GenerationBenchmarkWorkerRejectedException(List.of("rejected")));
        GenerationBenchmarkWorkerApplicationRunner runner =
                new GenerationBenchmarkWorkerApplicationRunner(service);

        runner.run(mock(ApplicationArguments.class));

        assertEquals(GenerationBenchmarkWorkerApplicationRunner.EXIT_GATE_REJECTED,
                runner.getExitCode());
    }

    @Test
    void executionFailureMustExposeNonzeroExitCode() {
        GenerationBenchmarkWorkerExecutionService service =
                mock(GenerationBenchmarkWorkerExecutionService.class);
        when(service.execute()).thenThrow(new IllegalStateException("失败"));
        GenerationBenchmarkWorkerApplicationRunner runner =
                new GenerationBenchmarkWorkerApplicationRunner(service);

        runner.run(mock(ApplicationArguments.class));

        assertEquals(GenerationBenchmarkWorkerApplicationRunner.EXIT_EXECUTION_FAILED,
                runner.getExitCode());
    }

    private GenerationBenchmarkWorkerResult result(String evidenceId) {
        String fingerprint = "a".repeat(64);
        return new GenerationBenchmarkWorkerResult(
                GenerationBenchmarkWorkerResult.CURRENT_SCHEMA_VERSION,
                GenerationBenchmarkWorkerResult.Status.PASSED,
                GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE,
                "7",
                fingerprint,
                1L,
                evidenceId,
                List.of(),
                GenerationBenchmarkWorkerTestFixtures.report(fingerprint, fingerprint)
        );
    }
}
