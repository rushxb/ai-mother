package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationSessionProperties;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.create.CreatePostGenerationValidationService;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.template.SlotFillGenerationService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlotFillGenerationPipelineTest {

    @Test
    void shouldReturnFailedResultWhenCreateSlotFillProducesNoPatch() {
        SlotFillGenerationService slotFillGenerationService = mock(SlotFillGenerationService.class);
        GenerationPerformanceMonitorService monitor = mock(GenerationPerformanceMonitorService.class);
        GenerationEventPublisher eventPublisher = new GenerationEventPublisher();
        GenerationTaskLifecycleService lifecycleService = mock(GenerationTaskLifecycleService.class);
        SlotFillGenerationPipeline pipeline = new SlotFillGenerationPipeline(
                lifecycleService,
                monitor,
                mock(CreatePostGenerationValidationService.class),
                eventPublisher,
                new GenerationSessionRegistry(new GenerationSessionProperties()),
                slotFillGenerationService
        );
        GenerationPipelineRequest request = request();
        when(slotFillGenerationService.tryGenerate(any(), any(), any())).thenReturn(null);

        Optional<GenerationTaskResult> result = pipeline.execute(request);

        assertTrue(result.isPresent());
        assertEquals("create", result.get().route());
        assertTrue(result.get().contentFlux()
                .filter(event -> GenerationStreamEvent.GENERATION_ERROR.equals(event.getType()))
                .blockFirst(Duration.ofSeconds(2))
                .getText()
                .contains("CREATE 模板生成失败"));
        verify(monitor, timeout(1000)).finishTask(result.get().taskId(), "failed");
        verify(lifecycleService).startGeneration(
                result.get().taskId(),
                request.taskRequest().app(),
                request.taskRequest().loginUser(),
                request.codeGenType(),
                request.codeGenType(),
                request.taskRequest().message(),
                request.taskRequest().message(),
                true,
                "create",
                "create",
                com.rush.rushaicodemother.constant.AppConstant.GENERATING_STAGE_CREATE
        );
        verify(lifecycleService, timeout(1000)).completeGeneration(
                result.get().taskId(), 1L, GenerationTaskStatus.FAILED,
                "create_generation_failed");
        assertTrue(eventPublisher.recent(1L).stream()
                .anyMatch(event -> event.message().contains("CREATE 模板生成失败")));
    }

    @Test
    void shouldReturnImmediatelyAndContinueCreateGenerationInBackground() {
        SlotFillGenerationService slotFillGenerationService = mock(SlotFillGenerationService.class);
        GenerationPerformanceMonitorService monitor = mock(GenerationPerformanceMonitorService.class);
        SlotFillGenerationPipeline pipeline = new SlotFillGenerationPipeline(
                mock(GenerationTaskLifecycleService.class),
                monitor,
                mock(CreatePostGenerationValidationService.class),
                new GenerationEventPublisher(),
                new GenerationSessionRegistry(new GenerationSessionProperties()),
                slotFillGenerationService
        );
        GenerationPipelineRequest request = request();
        when(slotFillGenerationService.tryGenerate(any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return null;
        });

        long startedAt = System.nanoTime();
        Optional<GenerationTaskResult> result = pipeline.execute(request);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertTrue(result.isPresent());
        assertTrue(elapsedMs < 200, "CREATE execute should return before slot fill finishes");
        assertTrue(result.get().contentFlux()
                .filter(event -> GenerationStreamEvent.GENERATION_ERROR.equals(event.getType()))
                .blockFirst(Duration.ofSeconds(2))
                .getText()
                .contains("CREATE 模板生成失败"));
    }

    @Test
    void shouldNotExposeCreateFailureCauseThroughStreamOrReplayEvent() {
        SlotFillGenerationService slotFillGenerationService = mock(SlotFillGenerationService.class);
        GenerationEventPublisher eventPublisher = new GenerationEventPublisher();
        SlotFillGenerationPipeline pipeline = new SlotFillGenerationPipeline(
                mock(GenerationTaskLifecycleService.class),
                mock(GenerationPerformanceMonitorService.class),
                mock(CreatePostGenerationValidationService.class),
                eventPublisher,
                new GenerationSessionRegistry(new GenerationSessionProperties()),
                slotFillGenerationService
        );
        when(slotFillGenerationService.tryGenerate(any(), any(), any()))
                .thenThrow(new IllegalStateException("provider-api-key=secret-value"));

        GenerationTaskResult result = pipeline.execute(request()).orElseThrow();
        GenerationStreamEvent errorEvent = result.contentFlux()
                .filter(event -> GenerationStreamEvent.GENERATION_ERROR.equals(event.getType()))
                .blockFirst(Duration.ofSeconds(2));

        assertEquals("CREATE 模板生成失败，请稍后重试", errorEvent.getText());
        assertFalse(errorEvent.getData().toString().contains("secret-value"));
        assertFalse(eventPublisher.recent(1L).toString().contains("secret-value"));
    }

    private GenerationPipelineRequest request() {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        User user = new User();
        user.setId(2L);
        Path root = Path.of("target/test-workspace").toAbsolutePath().normalize();
        GenerationWorkspace workspace = new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.VUE_PROJECT,
                root,
                root,
                false,
                root,
                root,
                Set.of(),
                Set.of()
        );
        GenerationModeDecision decision = GenerationModeDecision.of(
                GenerationMode.CREATE,
                0.9,
                "missing workspace",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD
        );
        return new GenerationPipelineRequest(
                new GenerationTaskRequest(app, "做一个商城落地页", user),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace,
                decision
        );
    }
}
