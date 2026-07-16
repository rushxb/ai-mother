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
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import com.rush.rushaicodemother.orchestration.template.SlotFillGenerationService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlotFillGenerationPipelineTest {

    @Test
    void shouldCompleteUnifiedTaskAsFailedWhenCreateProducesNoPatch() {
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

        assertEquals(GenerationTaskStatus.FAILED, outcome.terminalStatus());
        GenerationStreamEvent error = request.execution().session().asFlux()
                .filter(event -> GenerationStreamEvent.GENERATION_ERROR.equals(event.getType()))
                .blockFirst(Duration.ofSeconds(1));
        assertNotNull(error);
        verify(monitor).finishTask("create-task-1", "failed");
        verify(lifecycleService).completeGeneration(
                "create-task-1", 1L, GenerationTaskStatus.FAILED, "create_generation_failed");
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

        verify(lifecycleService).startGeneration(
                org.mockito.ArgumentMatchers.eq("stable-task-id"),
                any(App.class), any(User.class),
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

        pipeline.execute(request);
        GenerationStreamEvent error = request.execution().session().asFlux()
                .filter(event -> GenerationStreamEvent.GENERATION_ERROR.equals(event.getType()))
                .blockFirst(Duration.ofSeconds(1));

        assertNotNull(error);
        assertEquals("CREATE 模板生成失败，请稍后重试", error.getText());
        assertFalse(error.getData().toString().contains("secret-value"));
        assertFalse(eventPublisher.recent(1L).toString().contains("secret-value"));
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
                FallbackPolicy.NONE, ExpectedValidationLevel.BUILD);
        GenerationTaskRequest taskRequest = new GenerationTaskRequest(app, "做一个商城落地页", user);
        GenerationExecutionContext context = new GenerationExecutionContext(
                taskId, 1L, 2L, Instant.now(), new GenerationRuntimeProperties().toLimits(), Clock.systemUTC());
        GenerationSession session = new GenerationSession(null, context);
        return new GenerationPipelineRequest(
                taskRequest, CodeGenTypeEnum.VUE_PROJECT, workspace, decision,
                new GenerationTaskExecution(taskId, session, context, Instant.now()));
    }
}
