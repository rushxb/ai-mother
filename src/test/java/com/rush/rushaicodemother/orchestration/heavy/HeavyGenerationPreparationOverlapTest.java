package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.config.GenerationMemoryContextProperties;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationContextPreparationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationResult;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrator;
import com.rush.rushaicodemother.orchestration.context.GenerationMemoryContextOverlapExecutor;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.routing.HeavyGenerationIntentAssembler;
import com.rush.rushaicodemother.orchestration.routing.HeavyGenerationIntentDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.GenerationMemoryContextService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeavyGenerationPreparationOverlapTest {

    @Test
    void disabledPolicyMustKeepMemoryBuildBeforeOrchestration() {
        AtomicBoolean memoryReady = new AtomicBoolean();
        GenerationMemoryContextService memoryService = mock(GenerationMemoryContextService.class);
        when(memoryService.buildGenerationMemoryContext(any(), any(), any(), any())).thenAnswer(invocation -> {
            memoryReady.set(true);
            return "memory";
        });
        GenerationOrchestrator orchestrator = request -> {
            assertTrue(memoryReady.get());
            assertEquals("memory", request.resolveMemoryContext());
            return result(request.taskId());
        };

        try (GenerationMemoryContextOverlapExecutor overlapExecutor = overlapExecutor(false)) {
            service(memoryService, orchestrator, overlapExecutor)
                    .prepare("task-sequential-memory", app(), "更新页面");
        }
    }

    @Test
    void enabledPolicyMustOverlapMemoryBuildWithOrchestrationPreparation() throws Exception {
        CountDownLatch memoryStarted = new CountDownLatch(1);
        CountDownLatch releaseMemory = new CountDownLatch(1);
        AtomicBoolean memoryFinished = new AtomicBoolean();
        GenerationMemoryContextService memoryService = mock(GenerationMemoryContextService.class);
        when(memoryService.buildGenerationMemoryContext(any(), any(), any(), any())).thenAnswer(invocation -> {
            memoryStarted.countDown();
            assertTrue(releaseMemory.await(2, TimeUnit.SECONDS));
            memoryFinished.set(true);
            return "memory";
        });
        GenerationOrchestrator orchestrator = request -> {
            assertTrue(await(memoryStarted));
            assertFalse(memoryFinished.get());
            releaseMemory.countDown();
            assertEquals("memory", request.resolveMemoryContext());
            return result(request.taskId());
        };

        try (GenerationMemoryContextOverlapExecutor overlapExecutor = overlapExecutor(true)) {
            service(memoryService, orchestrator, overlapExecutor)
                    .prepare("task-overlapped-memory", app(), "更新页面");
        } finally {
            releaseMemory.countDown();
        }
    }

    @Test
    void unusedMemoryBuildMustBeCancelledWhenOrchestrationReturnsFromCheckpoint() throws Exception {
        CountDownLatch memoryStarted = new CountDownLatch(1);
        CountDownLatch memoryInterrupted = new CountDownLatch(1);
        CountDownLatch releaseMemory = new CountDownLatch(1);
        GenerationMemoryContextService memoryService = mock(GenerationMemoryContextService.class);
        when(memoryService.buildGenerationMemoryContext(any(), any(), any(), any())).thenAnswer(invocation -> {
            memoryStarted.countDown();
            try {
                releaseMemory.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                memoryInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("记忆构建被中断", failure);
            }
            return "memory";
        });
        GenerationOrchestrator orchestrator = request -> {
            assertTrue(await(memoryStarted));
            return result(request.taskId());
        };

        try (GenerationMemoryContextOverlapExecutor overlapExecutor = overlapExecutor(true)) {
            service(memoryService, orchestrator, overlapExecutor)
                    .prepare("task-restored-checkpoint", app(), "更新页面");

            assertTrue(memoryInterrupted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseMemory.countDown();
        }
    }

    @Test
    void orchestrationFailureMustCancelUnconsumedMemoryBuild() throws Exception {
        CountDownLatch memoryStarted = new CountDownLatch(1);
        CountDownLatch memoryInterrupted = new CountDownLatch(1);
        CountDownLatch releaseMemory = new CountDownLatch(1);
        GenerationMemoryContextService memoryService = mock(GenerationMemoryContextService.class);
        when(memoryService.buildGenerationMemoryContext(any(), any(), any(), any())).thenAnswer(invocation -> {
            memoryStarted.countDown();
            try {
                releaseMemory.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                memoryInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("记忆构建被中断", failure);
            }
            return "memory";
        });
        GenerationOrchestrator orchestrator = request -> {
            assertTrue(await(memoryStarted));
            throw new IllegalStateException("编排准备失败");
        };

        try (GenerationMemoryContextOverlapExecutor overlapExecutor = overlapExecutor(true)) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> service(memoryService, orchestrator, overlapExecutor)
                            .prepare("task-orchestration-failure", app(), "更新页面"));

            assertEquals("编排准备失败", failure.getMessage());
            assertTrue(memoryInterrupted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseMemory.countDown();
        }
    }

    private HeavyGenerationPreparationService service(
            GenerationMemoryContextService memoryService,
            GenerationOrchestrator orchestrator,
            GenerationMemoryContextOverlapExecutor overlapExecutor
    ) {
        App app = app();
        HeavyGenerationIntentAssembler intentAssembler = mock(HeavyGenerationIntentAssembler.class);
        when(intentAssembler.assemble(any(String.class), eq(app), any(String.class)))
                .thenReturn(new HeavyGenerationIntentDecision(
                        GenerationRoute.HEAVY_GENERATION,
                        "test",
                        1.0,
                        CodeGenTypeEnum.VUE_PROJECT,
                        CodeGenTypeEnum.VUE_PROJECT,
                        "更新页面",
                        "生成中",
                        true,
                        true
                ));
        return new HeavyGenerationPreparationService(
                intentAssembler,
                memoryService,
                orchestrator,
                mock(GenerationToolExecutionContextService.class),
                mock(GenerationWorkspaceService.class),
                overlapExecutor
        );
    }

    private GenerationMemoryContextOverlapExecutor overlapExecutor(boolean enabled) {
        GenerationMemoryContextProperties properties = new GenerationMemoryContextProperties();
        properties.setPreparationOverlapEnabled(enabled);
        properties.setMaxConcurrentPreparationOverlaps(2);
        properties.setPreparationOverlapTimeout(Duration.ofSeconds(2));
        properties.setShutdownTimeout(Duration.ofSeconds(2));
        GenerationExecutionContextService contextService = mock(GenerationExecutionContextService.class);
        when(contextService.clampTimeout(nullable(String.class), any(Duration.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return new GenerationMemoryContextOverlapExecutor(
                properties,
                new GenerationContextPreparationMetricsCollector(new SimpleMeterRegistry()),
                contextService
        );
    }

    private App app() {
        return App.builder()
                .id(982_001L)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .build();
    }

    private GenerationOrchestrationResult result(String taskId) {
        return new GenerationOrchestrationResult(
                CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                "生成中",
                "更新页面",
                List.of(),
                new HashMap<>(),
                null,
                Map.of(),
                taskId
        );
    }

    private boolean await(CountDownLatch latch) {
        try {
            return latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试等待记忆构建被中断", failure);
        }
    }
}
