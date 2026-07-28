package com.rush.rushaicodemother.core;

import com.rush.rushaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.core.saver.CodeFileSaverExecutor;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentExecutionRequest;
import com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentRuntime;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionService;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelTimeoutPolicy;
import com.rush.rushaicodemother.orchestration.runtime.model.RootModelRetryExecutor;
import com.rush.rushaicodemother.orchestration.runtime.model.RootModelRetryPolicy;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiCodeGeneratorFacadeExplicitAgentRuntimeTest {

    @Test
    void unmanagedProjectGenerationMustFailClosedBeforeAccessingDependencies() {
        GenerationAgentRuntime agentRuntime = mock(GenerationAgentRuntime.class);
        TestHarness harness = harness(agentRuntime);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> harness.facade().generateAndSaveCodeStream(
                        "生成管理后台", CodeGenTypeEnum.VUE_PROJECT, 11L));

        assertEquals("工程项目生成必须使用受管执行上下文", exception.getMessage());
        verifyNoInteractions(harness.serviceFactory(), agentRuntime);
    }

    @Test
    void managedProjectGenerationMustBypassTheImplicitAiServiceLoop() {
        GenerationAgentRuntime agentRuntime = mock(GenerationAgentRuntime.class);
        when(agentRuntime.start(any())).thenReturn(Flux.just(
                GenerationStreamEvent.generationStage(
                        "代码生成完成",
                        java.util.Map.of("stage", "codegen_done"))
        ));
        TestHarness harness = harness(agentRuntime);
        GenerationPerformanceProfile profile =
                GenerationPerformanceProfile.speedFirst();

        List<GenerationStreamEvent> events = harness.facade().generateAndSaveCodeStream(
                        "生成管理后台",
                        CodeGenTypeEnum.VUE_PROJECT,
                        11L,
                        () -> false,
                        ignored -> { },
                        profile,
                        harness.executionContext()
                )
                .collectList()
                .block();

        ArgumentCaptor<GenerationAgentExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(GenerationAgentExecutionRequest.class);
        verify(agentRuntime).start(requestCaptor.capture());
        GenerationAgentExecutionRequest request = requestCaptor.getValue();
        assertEquals("生成管理后台", request.userPrompt());
        assertEquals(CodeGenTypeEnum.VUE_PROJECT, request.codeGenType());
        assertEquals(harness.workspacePath().toString(), request.projectPath());
        assertSame(harness.executionContext(), request.executionContext());
        assertEquals(1, harness.executionContext().used(
                GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(1, events.size());
        verifyNoInteractions(harness.serviceFactory());
    }

    @Test
    void rootRetryMustStartANewAgentLedgerInsteadOfInheritingTheFailedTurnCount() {
        GenerationAgentRuntime agentRuntime = mock(GenerationAgentRuntime.class);
        AtomicInteger attempts = new AtomicInteger();
        when(agentRuntime.start(any())).thenAnswer(invocation -> {
            GenerationAgentExecutionRequest request = invocation.getArgument(0);
            request.executionContext().beginAgentAttempt(2);
            request.executionContext().reserveAgentModelTurn(2);
            if (attempts.incrementAndGet() == 1) {
                return Flux.<GenerationStreamEvent>error(
                        new java.util.concurrent.TimeoutException("模型暂时超时"));
            }
            return Flux.just(GenerationStreamEvent.generationStage(
                    "代码生成完成", java.util.Map.of("stage", "codegen_done")));
        });
        TestHarness harness = harness(agentRuntime);

        List<GenerationStreamEvent> events = harness.facade().generateAndSaveCodeStream(
                        "生成管理后台",
                        CodeGenTypeEnum.VUE_PROJECT,
                        11L,
                        () -> false,
                        ignored -> { },
                        GenerationPerformanceProfile.speedFirst(),
                        harness.executionContext()
                )
                .collectList()
                .block();

        assertEquals(1, events.size());
        assertEquals(2, attempts.get());
        assertEquals(2L, harness.executionContext().agentAttemptEpoch());
        assertEquals(1, harness.executionContext().agentModelTurnsStarted());
        assertEquals(2, harness.executionContext().used(
                GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        verify(agentRuntime, times(2)).start(any());
    }

    @Test
    void successfulWorkspaceMutationMustPreventRootReplayEvenBeforeAnEventIsEmitted() {
        GenerationAgentRuntime agentRuntime = mock(GenerationAgentRuntime.class);
        when(agentRuntime.start(any())).thenAnswer(invocation -> {
            GenerationAgentExecutionRequest request = invocation.getArgument(0);
            request.executionContext().recordSuccessfulWorkspaceMutations(1);
            return Flux.<GenerationStreamEvent>error(
                    new java.util.concurrent.TimeoutException("写入后模型连接超时"));
        });
        TestHarness harness = harness(agentRuntime);

        assertThrows(RuntimeException.class, () -> harness.facade().generateAndSaveCodeStream(
                        "生成管理后台",
                        CodeGenTypeEnum.VUE_PROJECT,
                        11L,
                        () -> false,
                        ignored -> { },
                        GenerationPerformanceProfile.speedFirst(),
                        harness.executionContext()
                )
                .collectList()
                .block());

        assertEquals(1, harness.executionContext().successfulWorkspaceMutationCount());
        assertEquals(1, harness.executionContext().used(
                GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        verify(agentRuntime, times(1)).start(any());
    }

    private TestHarness harness(GenerationAgentRuntime agentRuntime) {
        AiCodeGeneratorServiceFactory serviceFactory =
                mock(AiCodeGeneratorServiceFactory.class);
        GenerationWorkspaceService workspaceService =
                mock(GenerationWorkspaceService.class);
        GenerationWorkspace workspace = mock(GenerationWorkspace.class);
        Path workspacePath = Path.of("target", "explicit-agent-runtime", "vue_11")
                .toAbsolutePath().normalize();
        when(workspace.canonicalRootPath()).thenReturn(workspacePath);
        when(workspaceService.resolveExecution(
                any(GenerationExecutionFence.class),
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq(CodeGenTypeEnum.VUE_PROJECT)))
                .thenReturn(workspace);
        GenerationPerformanceMonitorService performanceMonitor =
                new GenerationPerformanceMonitorService();
        AiModelRuntimeProperties modelProperties = new AiModelRuntimeProperties();
        modelProperties.setRootModelRetryMinDelay(Duration.ofMillis(1));
        modelProperties.setRootModelRetryMaxDelay(Duration.ofMillis(1));
        modelProperties.setRootModelRetryJitter(0);
        RootModelRetryExecutor retryExecutor = new RootModelRetryExecutor(
                performanceMonitor, null, new RootModelRetryPolicy(modelProperties));
        GenerationStageAdmissionService stageAdmissionService =
                new GenerationStageAdmissionService(
                        new GenerationStageAdmissionProperties(),
                        new GenerationOrchestrationMetricsCollector(
                                new SimpleMeterRegistry()),
                        performanceMonitor
                );
        AiCodeGeneratorFacade facade = new AiCodeGeneratorFacade(
                serviceFactory,
                mock(CodeFileSaverExecutor.class),
                workspaceService,
                performanceMonitor,
                retryExecutor,
                stageAdmissionService,
                new GenerationModelTimeoutPolicy(modelProperties),
                new GenerationModelInvocationCancellationBridge(),
                agentRuntime
        );
        GenerationExecutionContext executionContext = new GenerationExecutionContext(
                "task-1",
                11L,
                7L,
                java.time.Instant.now(),
                new GenerationRuntimeProperties().toLimits(),
                Clock.systemUTC()
        );
        executionContext.bindExecutionFence(
                new GenerationExecutionFence("task-1", "worker-1", 1L));
        return new TestHarness(
                facade, executionContext, serviceFactory, workspacePath);
    }

    private record TestHarness(
            AiCodeGeneratorFacade facade,
            GenerationExecutionContext executionContext,
            AiCodeGeneratorServiceFactory serviceFactory,
            Path workspacePath
    ) {
    }
}
