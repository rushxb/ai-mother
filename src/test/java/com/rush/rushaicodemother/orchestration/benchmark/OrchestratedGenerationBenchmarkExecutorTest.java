package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationTaskOrchestrator;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrchestratedGenerationBenchmarkExecutorTest {

    private final GenerationBenchmarkRequestFactory requestFactory = new GenerationBenchmarkRequestFactory();
    private final GenerationEventPublisher eventPublisher = new GenerationEventPublisher();
    private final GenerationPerformanceMonitorService performanceMonitorService = new GenerationPerformanceMonitorService();

    @Test
    void shouldExecuteOrchestratorAndCollectSuccessfulBuildMetrics() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenAnswer(invocation -> {
            GenerationTaskRequest request = invocation.getArgument(0);
            String taskId = "bench-task-1";
            performanceMonitorService.startTask(
                    taskId,
                    request.app().getId(),
                    request.loginUser().getId(),
                    "create",
                    request.app().getCodeGenType(),
                    Instant.now(),
                    decision(GenerationMode.CREATE)
            );
            performanceMonitorService.recordCreateTelemetry(taskId, Map.of("aiCallCount", 2));
            performanceMonitorService.finishTask(taskId, "success");
            return new GenerationTaskResult(
                    taskId,
                    "create",
                    null,
                    Flux.just(GenerationStreamEvent.buildResult("build ok", Map.of("success", true)))
            );
        });
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);

        GenerationBenchmarkRunResult result = executor.execute(new GenerationBenchmarkTask(
                "create_bench", "CREATE", "vue_project", "生成后台", "build"
        ));

        assertTrue(result.success());
        assertTrue(result.buildPassed());
        assertEquals(2, result.aiCallCount());
        assertEquals("CREATE", result.mode());
    }

    @Test
    void shouldNotCountBuildTaskAsBuildPassedWithoutBuildEvidence() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenReturn(new GenerationTaskResult(
                "bench-task-2",
                "create",
                null,
                Flux.just(GenerationStreamEvent.agentEvent("done", Map.of()))
        ));
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);

        GenerationBenchmarkRunResult result = executor.execute(new GenerationBenchmarkTask(
                "create_bench", "CREATE", "vue_project", "生成后台", "build"
        ));

        assertTrue(result.success());
        assertFalse(result.buildPassed());
    }

    @Test
    void shouldCaptureTaskFailedEventAsBenchmarkFailure() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenAnswer(invocation -> {
            GenerationTaskRequest request = invocation.getArgument(0);
            eventPublisher.publish(request, GenerationEventType.TASK_FAILED, "failed", Map.of("reason", "boom"));
            return new GenerationTaskResult("bench-task-3", "agent_edit", null, Flux.never());
        });
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);

        GenerationBenchmarkRunResult result = executor.execute(new GenerationBenchmarkTask(
                "edit_bench", "AGENT_EDIT", "vue_project", "修复错误", "fast"
        ));

        assertFalse(result.success());
        assertEquals("boom", result.failureReason());
    }

    @Test
    void shouldUseValidationResultAsBuildEvidenceForEditBenchmarks() {
        GenerationTaskOrchestrator orchestrator = mock(GenerationTaskOrchestrator.class);
        when(orchestrator.start(any())).thenAnswer(invocation -> {
            GenerationTaskRequest request = invocation.getArgument(0);
            eventPublisher.publish(request, GenerationEventType.VALIDATION_RESULT, "validated", Map.of("status", "success"));
            eventPublisher.publish(request, GenerationEventType.TASK_DONE, "done", Map.of());
            return new GenerationTaskResult("bench-task-4", "agent_edit", null, Flux.never());
        });
        OrchestratedGenerationBenchmarkExecutor executor = executor(orchestrator);

        GenerationBenchmarkRunResult result = executor.execute(new GenerationBenchmarkTask(
                "edit_bench", "AGENT_EDIT", "vue_project", "修复错误", "build"
        ));

        assertTrue(result.success());
        assertTrue(result.buildPassed());
    }

    private OrchestratedGenerationBenchmarkExecutor executor(GenerationTaskOrchestrator orchestrator) {
        OrchestratedGenerationBenchmarkExecutor executor = new OrchestratedGenerationBenchmarkExecutor(
                requestFactory,
                orchestrator,
                eventPublisher,
                performanceMonitorService
        );
        ReflectionTestUtils.setField(executor, "taskTimeout", Duration.ofMillis(200));
        return executor;
    }

    private GenerationModeDecision decision(GenerationMode mode) {
        return new GenerationModeDecision(
                mode,
                1.0,
                "test",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.FAST,
                ""
        );
    }
}
