package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.config.GenerationMemoryContextProperties;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationContextPreparationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationResult;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrator;
import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.context.GenerationMemoryContextOverlapExecutor;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeavyGenerationPreparationOverlapTest {

    @Test
    void frozenScenarioMustReachDagPreparationWithoutSecondaryRouting() {
        App app = app();
        String userMessage = "把现有项目升级为企业应用";
        GenerationScenarioDecision scenarioDecision = frozenScenario(
                CodeGenTypeEnum.FULL_STACK_PROJECT);
        HeavyGenerationIntentAssembler intentAssembler = mock(HeavyGenerationIntentAssembler.class);
        when(intentAssembler.assemble(app, userMessage, scenarioDecision))
                .thenReturn(new HeavyGenerationIntentDecision(
                        GenerationRoute.HEAVY_GENERATION,
                        "frozen scenario",
                        0.91,
                        CodeGenTypeEnum.VUE_PROJECT,
                        CodeGenTypeEnum.FULL_STACK_PROJECT,
                        userMessage,
                        "生成中",
                        true
                ));
        GenerationMemoryContextService memoryService = mock(GenerationMemoryContextService.class);
        when(memoryService.buildGenerationMemoryContext(
                "task-frozen-scenario", app, userMessage, CodeGenTypeEnum.FULL_STACK_PROJECT))
                .thenReturn("memory");
        GenerationOrchestrator orchestrator = request -> {
            assertSame(scenarioDecision, request.scenarioDecision());
            assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT, request.scenarioDecision().targetType());
            assertEquals("memory", request.resolveMemoryContext());
            return result(request.taskId(), CodeGenTypeEnum.FULL_STACK_PROJECT);
        };

        try (GenerationMemoryContextOverlapExecutor overlapExecutor = overlapExecutor(false)) {
            GenerationPreparation preparation = new HeavyGenerationPreparationService(
                    intentAssembler,
                    memoryService,
                    orchestrator,
                    mock(GenerationToolExecutionContextService.class),
                    mock(GenerationWorkspaceService.class),
                    overlapExecutor
            ).prepare(
                    "task-frozen-scenario",
                    app,
                    userMessage,
                    GenerationPlanningVariant.CURRENT_DAG,
                    scenarioDecision
            );

            assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT, preparation.targetType());
        }
    }

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
            prepareFrozen(
                    service(memoryService, orchestrator, overlapExecutor),
                    "task-sequential-memory",
                    "更新页面"
            );
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
            prepareFrozen(
                    service(memoryService, orchestrator, overlapExecutor),
                    "task-overlapped-memory",
                    "更新页面"
            );
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
            prepareFrozen(
                    service(memoryService, orchestrator, overlapExecutor),
                    "task-restored-checkpoint",
                    "更新页面"
            );

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
                    () -> prepareFrozen(
                            service(memoryService, orchestrator, overlapExecutor),
                            "task-orchestration-failure",
                            "更新页面"
                    ));

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
        GenerationScenarioDecision scenarioDecision = frozenScenario(CodeGenTypeEnum.VUE_PROJECT);
        HeavyGenerationIntentAssembler intentAssembler = mock(HeavyGenerationIntentAssembler.class);
        when(intentAssembler.assemble(eq(app), any(String.class), eq(scenarioDecision)))
                .thenReturn(new HeavyGenerationIntentDecision(
                        GenerationRoute.HEAVY_GENERATION,
                        "test",
                        1.0,
                        CodeGenTypeEnum.VUE_PROJECT,
                        CodeGenTypeEnum.VUE_PROJECT,
                        "更新页面",
                        "生成中",
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

    private GenerationPreparation prepareFrozen(HeavyGenerationPreparationService service,
                                                 String taskId,
                                                 String userMessage) {
        return service.prepare(
                taskId,
                app(),
                userMessage,
                GenerationPlanningVariant.CURRENT_DAG,
                frozenScenario(CodeGenTypeEnum.VUE_PROJECT)
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
        return result(taskId, CodeGenTypeEnum.VUE_PROJECT);
    }

    private GenerationOrchestrationResult result(String taskId, CodeGenTypeEnum targetType) {
        return new GenerationOrchestrationResult(
                CodeGenTypeEnum.VUE_PROJECT,
                targetType,
                CodeGenTypeEnum.VUE_PROJECT.canUpgradeTo(targetType),
                "生成中",
                "更新页面",
                List.of(),
                new HashMap<>(),
                null,
                Map.of(),
                taskId
        );
    }

    private GenerationScenarioDecision frozenScenario(CodeGenTypeEnum targetType) {
        return GenerationScenarioDecision.restoreLegacy(
                IntentProfile.unknown(),
                targetType,
                GenerationResourceRequirements.none(),
                new GenerationModeDecision(
                        GenerationMode.HEAVY_EXPERT,
                        0.91,
                        "frozen scenario",
                        FallbackPolicy.NONE,
                        ExpectedValidationLevel.EXPERT,
                        ""
                ),
                10
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
