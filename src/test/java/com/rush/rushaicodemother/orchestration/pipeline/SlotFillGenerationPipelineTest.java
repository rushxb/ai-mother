package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.create.CreatePostGenerationValidationService;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import com.rush.rushaicodemother.orchestration.template.SlotFillGenerationService;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlotFillGenerationPipelineTest {

    @Test
    void shouldHandoffToHeavyExpertWhenCreateProducesNoPatch() {
        SlotFillGenerationService slotFillService = mock(SlotFillGenerationService.class);
        GenerationPerformanceMonitorService monitor = mock(GenerationPerformanceMonitorService.class);
        GenerationEventPublisher eventPublisher = new GenerationEventPublisher();
        GenerationTaskLifecycleService lifecycleService = mock(GenerationTaskLifecycleService.class);
        SlotFillGenerationPipeline pipeline = new SlotFillGenerationPipeline(
                lifecycleService, monitor, mock(CreatePostGenerationValidationService.class),
                eventPublisher, slotFillService);
        GenerationPipelineRequest request = request("create-task-1");
        when(slotFillService.tryGenerate(any(), any(), any())).thenReturn(null);

        GenerationPipelineOutcome outcome = pipeline.execute(request);

        assertEquals(GenerationPipelineDisposition.FALLBACK, outcome.disposition());
        assertNull(outcome.terminalStatus());
        assertEquals("create_generation_failed", outcome.reason());
        assertNull(outcome.resultSummary());
        List<GenerationStreamEvent> events = request.execution().session().asFlux()
                .take(Duration.ofMillis(30))
                .collectList()
                .block(Duration.ofSeconds(1));
        assertNotNull(events);
        assertTrue(events.stream().noneMatch(
                event -> GenerationStreamEvent.GENERATION_ERROR.equals(event.getType())));
        assertTrue(eventPublisher.recent(1L).stream().noneMatch(
                event -> event.type() == GenerationEventType.TASK_FAILED));
        verify(monitor, never()).finishTask("create-task-1", "failed");
        verify(lifecycleService, never()).completeGeneration(
                org.mockito.ArgumentMatchers.eq("create-task-1"),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(GenerationTaskStatus.FAILED),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void pipelineMustUsePreallocatedTaskId() {
        SlotFillGenerationService slotFillService = mock(SlotFillGenerationService.class);
        GenerationTaskLifecycleService lifecycleService = mock(GenerationTaskLifecycleService.class);
        SlotFillGenerationPipeline pipeline = new SlotFillGenerationPipeline(
                lifecycleService, mock(GenerationPerformanceMonitorService.class),
                mock(CreatePostGenerationValidationService.class), new GenerationEventPublisher(), slotFillService);
        GenerationPipelineRequest request = request("stable-task-id");
        when(slotFillService.tryGenerate(any(), any(), any())).thenReturn(null);

        pipeline.execute(request);

        verify(lifecycleService).startOrTransitionGeneration(
                org.mockito.ArgumentMatchers.eq("stable-task-id"),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(2L),
                org.mockito.ArgumentMatchers.eq(CodeGenTypeEnum.VUE_PROJECT),
                org.mockito.ArgumentMatchers.eq(CodeGenTypeEnum.VUE_PROJECT),
                any(), any(), org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq("create"),
                org.mockito.ArgumentMatchers.eq("create"),
                any());
    }

    @Test
    void shouldNotExposeCreateFailureCauseThroughStreamOrReplayEvent() {
        SlotFillGenerationService slotFillService = mock(SlotFillGenerationService.class);
        GenerationEventPublisher eventPublisher = new GenerationEventPublisher();
        SlotFillGenerationPipeline pipeline = new SlotFillGenerationPipeline(
                mock(GenerationTaskLifecycleService.class), mock(GenerationPerformanceMonitorService.class),
                mock(CreatePostGenerationValidationService.class), eventPublisher, slotFillService);
        when(slotFillService.tryGenerate(any(), any(), any()))
                .thenThrow(new IllegalStateException("provider-api-key=secret-value"));
        GenerationPipelineRequest request = request("create-secret-test");

        GenerationPipelineOutcome outcome = pipeline.execute(request);
        List<GenerationStreamEvent> events = request.execution().session().asFlux()
                .take(Duration.ofMillis(30))
                .collectList()
                .block(Duration.ofSeconds(1));

        assertEquals(GenerationPipelineDisposition.FALLBACK, outcome.disposition());
        assertEquals("create_generation_failed", outcome.reason());
        assertNotNull(events);
        assertTrue(events.stream().noneMatch(
                event -> GenerationStreamEvent.GENERATION_ERROR.equals(event.getType())));
        assertFalse(events.toString().contains("secret-value"));
        assertFalse(eventPublisher.recent(1L).toString().contains("secret-value"));
        assertNull(outcome.resultSummary());
    }

    @Test
    void failureAfterWorkspaceMutationMustNotReplayThroughHeavyFallback() {
        SlotFillGenerationService slotFillService = mock(SlotFillGenerationService.class);
        GenerationEventPublisher eventPublisher = new GenerationEventPublisher();
        SlotFillGenerationPipeline pipeline = new SlotFillGenerationPipeline(
                mock(GenerationTaskLifecycleService.class), mock(GenerationPerformanceMonitorService.class),
                mock(CreatePostGenerationValidationService.class), eventPublisher, slotFillService);
        GenerationPipelineRequest request = request("create-post-write-failure");
        when(slotFillService.tryGenerate(any(), any(), any())).thenAnswer(invocation -> {
            request.requireExecution().executionContext().recordSuccessfulWorkspaceMutations(1);
            throw new IllegalStateException("provider-api-key=secret-value");
        });

        GenerationPipelineOutcome outcome = pipeline.execute(request);

        assertEquals(GenerationPipelineDisposition.COMPLETED, outcome.disposition());
        assertEquals(GenerationTaskStatus.FAILED, outcome.terminalStatus());
        assertEquals("create_validation_failed", outcome.reason());
        assertTrue(outcome.resultSummary().contains("CREATE 写入后处理"));
        assertFalse(outcome.resultSummary().contains("secret-value"));
        assertTrue(eventPublisher.recent(1L).stream().anyMatch(
                event -> event.type() == GenerationEventType.TASK_FAILED));
    }

    @Test
    void validationFailureAfterAutoRepairMustTerminateWithoutHeavyHandoff() {
        SlotFillGenerationService slotFillService = mock(SlotFillGenerationService.class);
        GenerationPerformanceMonitorService monitor = mock(GenerationPerformanceMonitorService.class);
        CreatePostGenerationValidationService validationService =
                mock(CreatePostGenerationValidationService.class);
        GenerationEventPublisher eventPublisher = new GenerationEventPublisher();
        SlotFillGenerationPipeline pipeline = new SlotFillGenerationPipeline(
                mock(GenerationTaskLifecycleService.class), monitor, validationService,
                eventPublisher, slotFillService);
        SlotFillResult result = SlotFillResult.success(
                "vue-base", List.of("hero"), List.of(), "模板已生成", 128);
        when(slotFillService.tryGenerate(any(), any(), any())).thenReturn(result);
        when(validationService.validate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CreatePostGenerationValidationService.ValidationOutcome(
                        false, true, "provider-api-key=secret-value"));
        GenerationPipelineRequest request = request("create-validation-fallback");

        GenerationPipelineOutcome outcome = pipeline.execute(request);

        assertEquals(GenerationPipelineDisposition.COMPLETED, outcome.disposition());
        assertEquals(GenerationTaskStatus.FAILED, outcome.terminalStatus());
        assertEquals("create_validation_failed", outcome.reason());
        assertTrue(outcome.resultSummary().contains("自动修复预算已用尽"));
        assertTrue(eventPublisher.recent(1L).stream().anyMatch(
                event -> event.type() == GenerationEventType.TASK_FAILED));
        assertFalse(eventPublisher.recent(1L).toString().contains("secret-value"));
        assertFalse(outcome.resultSummary().contains("secret-value"));
        verify(monitor).finishTask("create-validation-fallback", "failed");
        verify(monitor).recordCreateTelemetry(
                org.mockito.ArgumentMatchers.eq("create-validation-fallback"),
                org.mockito.ArgumentMatchers.argThat(telemetry -> Boolean.TRUE.equals(telemetry.get("validationFailed"))
                        && Boolean.FALSE.equals(telemetry.get("fallback"))));
    }

    @Test
    void lifecycleInitializationFailureMustRemainTerminal() {
        GenerationTaskLifecycleService lifecycleService = mock(GenerationTaskLifecycleService.class);
        GenerationPerformanceMonitorService monitor = mock(GenerationPerformanceMonitorService.class);
        GenerationEventPublisher eventPublisher = new GenerationEventPublisher();
        SlotFillGenerationPipeline pipeline = new SlotFillGenerationPipeline(
                lifecycleService, monitor, mock(CreatePostGenerationValidationService.class),
                eventPublisher, mock(SlotFillGenerationService.class));
        doThrow(new IllegalStateException("trace unavailable"))
                .when(lifecycleService).startOrTransitionGeneration(
                        any(), any(), any(), any(), any(), any(), any(),
                        org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any());
        GenerationPipelineRequest request = request("create-init-failed");

        GenerationPipelineOutcome outcome = pipeline.execute(request);

        assertEquals(GenerationPipelineDisposition.COMPLETED, outcome.disposition());
        assertEquals(GenerationTaskStatus.FAILED, outcome.terminalStatus());
        assertEquals("create_generation_failed", outcome.reason());
        assertTrue(outcome.resultSummary().contains("CREATE 生命周期初始化"));
        assertTrue(eventPublisher.recent(1L).stream().anyMatch(
                event -> event.type() == GenerationEventType.TASK_FAILED));
        verify(monitor).finishTask("create-init-failed", "failed");
    }

    private GenerationPipelineRequest request(String taskId) {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        User user = new User();
        user.setId(2L);
        Path root = Path.of("target/test-workspace").toAbsolutePath().normalize();
        GenerationWorkspace workspace = new GenerationWorkspace(
                1L, CodeGenTypeEnum.VUE_PROJECT, root, root, false, root, root, Set.of(), Set.of());
        GenerationModeDecision decision = GenerationModeDecision.of(
                GenerationMode.CREATE, 0.9, "missing workspace",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT, ExpectedValidationLevel.BUILD);
        GenerationTaskRequest taskRequest = new GenerationTaskRequest(app, "做一个商城落地页", user);
        GenerationExecutionContext context = new GenerationExecutionContext(
                taskId, 1L, 2L, Instant.now(), new GenerationRuntimeProperties().toLimits(), Clock.systemUTC());
        GenerationExecutionFence fence = new GenerationExecutionFence(taskId, "worker-a", 3L);
        context.bindExecutionFence(fence);
        GenerationSession session = new GenerationSession(null, context);
        return new GenerationPipelineRequest(
                taskRequest, CodeGenTypeEnum.VUE_PROJECT, workspace, decision,
                new GenerationTaskExecution(taskId, session, context, fence, Instant.now()));
    }
}
