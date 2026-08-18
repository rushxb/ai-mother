package com.rush.rushaicodemother.core;

import com.rush.rushaicodemother.ai.AiCodeGeneratorService;
import com.rush.rushaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.core.parser.CodeParserExecutor;
import com.rush.rushaicodemother.core.parser.HtmlCodeParser;
import com.rush.rushaicodemother.core.parser.MultiFileCodeParser;
import com.rush.rushaicodemother.core.saver.CodeFileSaverExecutor;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentRuntime;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionService;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCallTimeoutException;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCancellationScope;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelTimeoutPolicy;
import com.rush.rushaicodemother.orchestration.runtime.model.RootModelRetryExecutor;
import com.rush.rushaicodemother.orchestration.runtime.model.RootModelRetryPolicy;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCodeGeneratorFacadeStreamingAdapterTest {

    private static final long APP_ID = 42L;

    @Test
    void streamPersistenceFailureMustHideParserDetailsAndPreserveCause() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService generatorService = mock(AiCodeGeneratorService.class);
        when(serviceFactory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.HTML, null))
                .thenReturn(generatorService);
        TestTokenStream tokenStream = new TestTokenStream(stream -> {
            stream.emitPartialResponse(
                    "internal-template-path=C:/secret/template", new TestStreamingHandle());
            stream.emitCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("internal-template-path=C:/secret/template"))
                    .build());
        });
        when(generatorService.generateHtmlCodeStream(
                eq("build invalid html"), any(InvocationParameters.class)))
                .thenReturn(tokenStream);
        AiCodeGeneratorFacade facade = facade(serviceFactory, mock(CodeFileSaverExecutor.class));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> facade.generateAndSaveCodeStream(
                                "build invalid html",
                                CodeGenTypeEnum.HTML,
                                APP_ID
                        )
                        .collectList()
                        .block()
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertEquals("保存生成代码失败，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("internal-template-path"));
        assertTrue(exception.getCause() instanceof BusinessException);
    }

    @Test
    void modelAttemptMustHaveWallClockLimitEvenWhenProviderKeepsEmitting() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService generatorService = mock(AiCodeGeneratorService.class);
        when(serviceFactory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.HTML, null))
                .thenReturn(generatorService);
        TestStreamingHandle streamingHandle = new TestStreamingHandle();
        TestTokenStream tokenStream = new TestTokenStream(stream -> Thread.ofVirtual().start(() -> {
            while (!streamingHandle.isCancelled()) {
                stream.emitPartialResponse("late", streamingHandle);
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
        }));
        when(generatorService.generateHtmlCodeStream(
                eq("slow html"), any(InvocationParameters.class)))
                .thenReturn(tokenStream);
        when(serviceFactory.createTaskScopedAiCodeGeneratorService(
                eq(APP_ID), eq(CodeGenTypeEnum.HTML), isNull(), any(Duration.class),
                any(Runnable.class), any(Runnable.class)))
                .thenReturn(generatorService);
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-model-timeout",
                APP_ID,
                7L,
                Instant.now(),
                limits(Duration.ofSeconds(10), Duration.ofMillis(80)),
                Clock.systemUTC()
        );
        AiCodeGeneratorFacade facade = facade(serviceFactory, mock(CodeFileSaverExecutor.class));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                facade.generateAndSaveCodeStream(
                                "slow html",
                                CodeGenTypeEnum.HTML,
                                APP_ID,
                                () -> false,
                                ignored -> { },
                                null,
                                context
                        )
                        .collectList()
                        .block());
        GenerationModelCallTimeoutException timeout = findCause(
                failure, GenerationModelCallTimeoutException.class);
        assertNotNull(timeout);
        assertEquals("wall-clock", timeout.timeoutKind());
        assertEquals(1, streamingHandle.cancellationCount());
    }

    @Test
    void simpleStreamFirstActivityMustDisableFirstSignalTimeout() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService generatorService = mock(AiCodeGeneratorService.class);
        CodeFileSaverExecutor saverExecutor = mock(CodeFileSaverExecutor.class);
        when(serviceFactory.createTaskScopedAiCodeGeneratorService(
                eq(APP_ID), eq(CodeGenTypeEnum.HTML), isNull(), any(Duration.class),
                any(Runnable.class), any(Runnable.class)))
                .thenReturn(generatorService);
        TestStreamingHandle streamingHandle = new TestStreamingHandle();
        String html = "<!DOCTYPE html><html><head><title>测试</title></head>"
                + "<body>完成</body></html>";
        TestTokenStream tokenStream = new TestTokenStream(stream -> {
            stream.emitPartialResponse(html, streamingHandle);
            Thread.ofVirtual().start(() -> {
                LockSupport.parkNanos(Duration.ofMillis(400).toNanos());
                stream.emitCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(html))
                        .build());
            });
        });
        when(generatorService.generateHtmlCodeStream(
                eq("正常慢流"), any(InvocationParameters.class)))
                .thenReturn(tokenStream);
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-simple-first-signal",
                APP_ID,
                7L,
                Instant.now(),
                limits(Duration.ofSeconds(10), Duration.ofSeconds(2)),
                Clock.systemUTC()
        );
        AiModelRuntimeProperties runtimeProperties = retryProperties(Duration.ofMillis(1));
        runtimeProperties.setFirstSignalTimeout(Duration.ofMillis(200));

        List<GenerationStreamEvent> events = facade(
                serviceFactory,
                saverExecutor,
                new GenerationPerformanceMonitorService(),
                runtimeProperties,
                new AiModelMetricsCollector(new SimpleMeterRegistry())
        ).generateAndSaveCodeStream(
                        "正常慢流",
                        CodeGenTypeEnum.HTML,
                        APP_ID,
                        () -> false,
                        ignored -> { },
                        null,
                        context
                )
                .collectList()
                .block();

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.AI_DELTA.equals(event.getType())));
        assertEquals(0, streamingHandle.cancellationCount());
        verify(saverExecutor, times(1)).executeSaver(
                any(), eq(CodeGenTypeEnum.HTML), eq(APP_ID), any(GenerationWorkspace.class));
    }

    @Test
    void simpleStreamFirstSignalTimeoutMustCancelTransportScope() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService generatorService = mock(AiCodeGeneratorService.class);
        when(serviceFactory.createTaskScopedAiCodeGeneratorService(
                eq(APP_ID), eq(CodeGenTypeEnum.HTML), isNull(), any(Duration.class),
                any(Runnable.class), any(Runnable.class)))
                .thenReturn(generatorService);
        AtomicInteger transportCancellations = new AtomicInteger();
        when(generatorService.generateHtmlCodeStream(
                eq("首活动前超时"), any(InvocationParameters.class)))
                .thenAnswer(invocation -> {
                    InvocationParameters parameters = invocation.getArgument(1);
                    GenerationModelCancellationScope scope = parameters.get(
                            GenerationModelCancellationScope.INVOCATION_PARAMETER);
                    scope.register(transportCancellations::incrementAndGet);
                    return new TestTokenStream(ignored -> { });
                });
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-simple-first-signal-timeout",
                APP_ID,
                7L,
                Instant.now(),
                limits(Duration.ofSeconds(10), Duration.ofSeconds(1)),
                Clock.systemUTC()
        );
        AiModelRuntimeProperties runtimeProperties = retryProperties(Duration.ofMillis(1));
        runtimeProperties.setFirstSignalTimeout(Duration.ofMillis(40));
        AtomicReference<GenerationCancellationHandle> publishedHandle = new AtomicReference<>();

        RuntimeException failure = assertThrows(RuntimeException.class, () -> facade(
                serviceFactory,
                mock(CodeFileSaverExecutor.class),
                new GenerationPerformanceMonitorService(),
                runtimeProperties,
                new AiModelMetricsCollector(new SimpleMeterRegistry())
        ).generateAndSaveCodeStream(
                        "首活动前超时",
                        CodeGenTypeEnum.HTML,
                        APP_ID,
                        () -> false,
                        publishedHandle::set,
                        null,
                        context
                )
                .collectList()
                .block());

        GenerationModelCallTimeoutException timeout = findCause(
                failure, GenerationModelCallTimeoutException.class);
        assertNotNull(timeout);
        assertEquals("first-signal", timeout.timeoutKind());
        assertNotNull(publishedHandle.get());
        assertEquals(1, transportCancellations.get());
    }

    @Test
    void simpleStreamSubscriberCancellationMustCancelOfficialHandle() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService generatorService = mock(AiCodeGeneratorService.class);
        when(serviceFactory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.HTML, null))
                .thenReturn(generatorService);
        TestStreamingHandle streamingHandle = new TestStreamingHandle();
        TestTokenStream tokenStream = new TestTokenStream(stream ->
                stream.emitPartialResponse("<html>", streamingHandle));
        when(generatorService.generateHtmlCodeStream(
                eq("用户取消"), any(InvocationParameters.class)))
                .thenReturn(tokenStream);
        AtomicReference<GenerationCancellationHandle> publishedHandle = new AtomicReference<>();

        GenerationStreamEvent event = facade(serviceFactory, mock(CodeFileSaverExecutor.class))
                .generateAndSaveCodeStream(
                        "用户取消",
                        CodeGenTypeEnum.HTML,
                        APP_ID,
                        () -> false,
                        publishedHandle::set
                )
                .blockFirst();

        assertNotNull(event);
        assertNotNull(publishedHandle.get());
        assertEquals(1, streamingHandle.cancellationCount());
    }

    @Test
    void simpleStreamSynchronousFailureMustCancelRegisteredTransport() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService generatorService = mock(AiCodeGeneratorService.class);
        when(serviceFactory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.HTML, null))
                .thenReturn(generatorService);
        AtomicInteger transportCancellations = new AtomicInteger();
        when(generatorService.generateHtmlCodeStream(
                eq("同步失败"), any(InvocationParameters.class)))
                .thenAnswer(invocation -> {
                    InvocationParameters parameters = invocation.getArgument(1);
                    GenerationModelCancellationScope scope = parameters.get(
                            GenerationModelCancellationScope.INVOCATION_PARAMETER);
                    scope.register(transportCancellations::incrementAndGet);
                    throw new IllegalStateException("模型流创建失败");
                });

        assertThrows(IllegalStateException.class, () ->
                facade(serviceFactory, mock(CodeFileSaverExecutor.class))
                        .generateAndSaveCodeStream(
                                "同步失败", CodeGenTypeEnum.HTML, APP_ID)
                        .collectList()
                        .block());

        assertEquals(1, transportCancellations.get());
    }

    private GenerationExecutionLimits limits(Duration taskTimeout, Duration modelCallTimeout) {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 3);
        }
        return new GenerationExecutionLimits(
                taskTimeout, modelCallTimeout, Duration.ofMillis(1), budgets);
    }

    private AiCodeGeneratorFacade facade(
            AiCodeGeneratorServiceFactory serviceFactory,
            CodeFileSaverExecutor saverExecutor
    ) {
        return facade(serviceFactory, saverExecutor, new GenerationPerformanceMonitorService());
    }

    private AiCodeGeneratorFacade facade(
            AiCodeGeneratorServiceFactory serviceFactory,
            CodeFileSaverExecutor saverExecutor,
            GenerationPerformanceMonitorService performanceMonitorService
    ) {
        return facade(
                serviceFactory,
                saverExecutor,
                performanceMonitorService,
                retryProperties(Duration.ofMillis(1)),
                new AiModelMetricsCollector(new SimpleMeterRegistry())
        );
    }

    private AiCodeGeneratorFacade facade(
            AiCodeGeneratorServiceFactory serviceFactory,
            CodeFileSaverExecutor saverExecutor,
            GenerationPerformanceMonitorService performanceMonitorService,
            AiModelRuntimeProperties runtimeProperties,
            AiModelMetricsCollector metricsCollector
    ) {
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        for (CodeGenTypeEnum codeGenType : CodeGenTypeEnum.values()) {
            Path workspaceRoot = Path.of(
                            "target", "test-work", "facade",
                            codeGenType.name().toLowerCase() + "_" + APP_ID)
                    .toAbsolutePath()
                    .normalize();
            GenerationWorkspace workspace = new GenerationWorkspace(
                    APP_ID,
                    codeGenType,
                    workspaceRoot,
                    workspaceRoot,
                    false,
                    workspaceRoot,
                    null,
                    Set.of(),
                    Set.of()
            );
            when(workspaceService.prepare(APP_ID, codeGenType)).thenReturn(workspace);
            when(workspaceService.resolve(APP_ID, codeGenType)).thenReturn(workspace);
            when(workspaceService.resolveExecution(
                    any(GenerationExecutionFence.class),
                    eq(APP_ID),
                    eq(codeGenType)
            )).thenReturn(workspace);
            when(saverExecutor.executeSaver(
                    any(), eq(codeGenType), eq(APP_ID), any(GenerationWorkspace.class)))
                    .thenReturn(workspaceRoot.toFile());
        }
        return new AiCodeGeneratorFacade(
                serviceFactory,
                new CodeParserExecutor(List.of(new HtmlCodeParser(), new MultiFileCodeParser())),
                saverExecutor,
                workspaceService,
                performanceMonitorService,
                new RootModelRetryExecutor(
                        performanceMonitorService,
                        metricsCollector,
                        new RootModelRetryPolicy(runtimeProperties)),
                stageAdmissionService(performanceMonitorService),
                new GenerationModelTimeoutPolicy(runtimeProperties),
                new GenerationModelInvocationCancellationBridge(),
                mock(GenerationAgentRuntime.class)
        );
    }

    private GenerationStageAdmissionService stageAdmissionService(
            GenerationPerformanceMonitorService performanceMonitorService) {
        GenerationStageAdmissionProperties properties = new GenerationStageAdmissionProperties();
        properties.setModelTurnMinimum(Duration.ofMillis(1));
        properties.setModelHandoffReserve(Duration.ofMillis(1));
        properties.setRepairModelMinimum(Duration.ofMillis(1));
        properties.setBuildMinimum(Duration.ofMillis(1));
        properties.setRuntimeValidationMinimum(Duration.ofMillis(1));
        properties.setTerminalizationReserve(Duration.ofMillis(1));
        return new GenerationStageAdmissionService(
                properties,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                performanceMonitorService
        );
    }

    private AiModelRuntimeProperties retryProperties(Duration delay) {
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        properties.setRootModelRetryMinDelay(delay);
        properties.setRootModelRetryMaxDelay(delay);
        properties.setRootModelRetryJitter(0);
        return properties;
    }

    private <T extends Throwable> T findCause(Throwable failure, Class<T> expectedType) {
        Throwable current = failure;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return expectedType.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static final class TestStreamingHandle implements StreamingHandle {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicInteger cancellationCount = new AtomicInteger();

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                cancellationCount.incrementAndGet();
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        private int cancellationCount() {
            return cancellationCount.get();
        }
    }

    private static final class TestTokenStream implements TokenStream {

        private final Consumer<TestTokenStream> startAction;
        private BiConsumer<PartialResponse, PartialResponseContext> partialResponseConsumer;
        private Consumer<ChatResponse> completeResponseConsumer;
        private Consumer<Throwable> errorConsumer;

        private TestTokenStream(Consumer<TestTokenStream> startAction) {
            this.startAction = startAction;
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> consumer) {
            return this;
        }

        @Override
        public TokenStream onPartialResponseWithContext(
                BiConsumer<PartialResponse, PartialResponseContext> consumer) {
            this.partialResponseConsumer = consumer;
            return this;
        }

        @Override
        public TokenStream onPartialThinkingWithContext(
                BiConsumer<PartialThinking, PartialThinkingContext> consumer) {
            return this;
        }

        @Override
        public TokenStream onPartialToolCallWithContext(
                BiConsumer<PartialToolCall, PartialToolCallContext> consumer) {
            return this;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<Content>> consumer) {
            return this;
        }

        @Override
        public TokenStream onIntermediateResponse(Consumer<ChatResponse> consumer) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(Consumer<ToolExecution> consumer) {
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(Consumer<ChatResponse> consumer) {
            this.completeResponseConsumer = consumer;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> consumer) {
            this.errorConsumer = consumer;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            startAction.accept(this);
        }

        private void emitPartialResponse(String text, StreamingHandle handle) {
            partialResponseConsumer.accept(
                    new PartialResponse(text), new PartialResponseContext(handle));
        }

        private void emitCompleteResponse(ChatResponse response) {
            completeResponseConsumer.accept(response);
        }
    }
}
