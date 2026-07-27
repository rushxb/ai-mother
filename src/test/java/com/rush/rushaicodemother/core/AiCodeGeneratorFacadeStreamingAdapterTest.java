package com.rush.rushaicodemother.core;

import com.rush.rushaicodemother.ai.AiCodeGeneratorService;
import com.rush.rushaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.core.saver.CodeFileSaverExecutor;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.orchestration.tool.DestructiveToolAction;
import com.rush.rushaicodemother.orchestration.tool.GenerationApprovalRequiredException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationModelTurnAdmissionException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionService;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCallTimeoutException;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCancellationScope;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
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
import dev.langchain4j.service.tool.ToolExecutionResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void publishesOfficialStreamingHandleAndCancelsItWhenSubscriberCancels() {
        TestStreamingHandle officialHandle = new TestStreamingHandle();
        TestTokenStream tokenStream = new TestTokenStream(stream ->
                stream.emitPartialResponse("hello", officialHandle));
        AiCodeGeneratorFacade facade = facade(tokenStream);
        AtomicReference<GenerationCancellationHandle> publishedHandle = new AtomicReference<>();

        GenerationStreamEvent firstEvent = facade.generateAndSaveCodeStream(
                        "build a page",
                        CodeGenTypeEnum.VUE_PROJECT,
                        APP_ID,
                        () -> false,
                        publishedHandle::set
                )
                .blockFirst();

        assertNotNull(firstEvent);
        assertEquals(GenerationStreamEvent.AI_DELTA, firstEvent.getType());
        assertEquals("hello", firstEvent.getText());
        assertNotNull(publishedHandle.get());
        assertEquals(1, officialHandle.cancellationCount());

        publishedHandle.get().cancel();

        assertEquals(1, officialHandle.cancellationCount());
    }

    @Test
    void privateThinkingMustBecomeStructuredProgressWithoutLeakingContent() {
        TestStreamingHandle officialHandle = new TestStreamingHandle();
        TestTokenStream tokenStream = new TestTokenStream(stream -> {
            stream.emitPartialThinking("private chain of thought", officialHandle);
            stream.emitPartialResponse("visible answer", officialHandle);
            stream.emitCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("done"))
                    .build());
        });

        List<GenerationStreamEvent> events = facade(tokenStream)
                .generateAndSaveCodeStream("build a page", CodeGenTypeEnum.VUE_PROJECT, APP_ID)
                .collectList()
                .block();

        assertNotNull(events);
        assertTrue(events.stream().noneMatch(event ->
                GenerationStreamEvent.AI_THINKING_DELTA.equals(event.getType())));
        assertTrue(events.stream().noneMatch(event ->
                event.toString().contains("private chain of thought")));
        assertEquals(List.of("running", "done"), events.stream()
                .filter(event -> GenerationStreamEvent.AGENT_EVENT.equals(event.getType()))
                .map(event -> String.valueOf(event.getData().get("status")))
                .toList());
        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.AI_DELTA.equals(event.getType())
                        && "visible answer".equals(event.getText())));
    }

    @Test
    void publishesCompleteToolRequestOnceAndNormalizesNullMetadata() {
        TestStreamingHandle officialHandle = new TestStreamingHandle();
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id(null)
                .name(null)
                .arguments(null)
                .build();
        ChatResponse intermediateResponse = ChatResponse.builder()
                .aiMessage(AiMessage.builder()
                        .toolExecutionRequests(List.of(toolRequest))
                        .build())
                .build();
        ChatResponse completeResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("done"))
                .build();
        TestTokenStream tokenStream = new TestTokenStream(stream -> {
            stream.emitPartialToolCall(PartialToolCall.builder()
                    .index(0)
                    .id("partial-id")
                    .name("partial-name")
                    .partialArguments("{")
                    .build(), officialHandle);
            stream.emitIntermediateResponse(intermediateResponse);
            stream.emitCompleteResponse(completeResponse);
        });

        List<GenerationStreamEvent> events = facade(tokenStream)
                .generateAndSaveCodeStream(
                        "build a page",
                        CodeGenTypeEnum.VUE_PROJECT,
                        APP_ID
                )
                .collectList()
                .block();

        assertNotNull(events);
        List<GenerationStreamEvent> toolCallEvents = events.stream()
                .filter(event -> GenerationStreamEvent.TOOL_CALL.equals(event.getType()))
                .toList();
        assertEquals(1, toolCallEvents.size());

        GenerationStreamEvent toolCallEvent = toolCallEvents.getFirst();
        assertEquals("", toolCallEvent.getText());
        assertEquals("", toolCallEvent.getData().get("toolName"));
        assertEquals("", toolCallEvent.getData().get("arguments"));
        assertEquals("", toolCallEvent.getData().get("requestId"));
        assertEquals(0, toolCallEvent.getData().get("toolIndex"));
        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.GENERATION_STAGE.equals(event.getType())));
    }

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
    void retryMustResolveAFreshAiServiceSoTheCircuitBreakerCanSelectAFallbackModel() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService primaryService = mock(AiCodeGeneratorService.class);
        AiCodeGeneratorService fallbackService = mock(AiCodeGeneratorService.class);
        TestTokenStream primaryStream = new TestTokenStream(stream ->
                stream.emitError(new dev.langchain4j.exception.TimeoutException("primary timeout")));
        TestTokenStream fallbackStream = new TestTokenStream(stream ->
                stream.emitCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("done")).build()));
        when(serviceFactory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE_PROJECT, null))
                .thenReturn(primaryService, fallbackService);
        when(primaryService.generateVueProjectCodeStream(APP_ID, "build with fallback"))
                .thenReturn(primaryStream);
        when(fallbackService.generateVueProjectCodeStream(APP_ID, "build with fallback"))
                .thenReturn(fallbackStream);

        List<GenerationStreamEvent> events = facade(serviceFactory, mock(CodeFileSaverExecutor.class))
                .generateAndSaveCodeStream("build with fallback", CodeGenTypeEnum.VUE_PROJECT, APP_ID)
                .collectList()
                .block();

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.GENERATION_STAGE.equals(event.getType())));
        verify(serviceFactory, times(2))
                .getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE_PROJECT, null);
    }

    @Test
    void retryMustStopAfterAnyVisibleEventToAvoidDuplicatingSideEffects() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService primaryService = mock(AiCodeGeneratorService.class);
        TestTokenStream primaryStream = new TestTokenStream(stream -> {
            stream.emitPartialResponse("visible", new TestStreamingHandle());
            stream.emitError(new dev.langchain4j.exception.TimeoutException("late timeout"));
        });
        when(serviceFactory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE_PROJECT, null))
                .thenReturn(primaryService);
        when(primaryService.generateVueProjectCodeStream(APP_ID, "do not duplicate"))
                .thenReturn(primaryStream);

        assertThrows(RuntimeException.class, () -> facade(serviceFactory, mock(CodeFileSaverExecutor.class))
                .generateAndSaveCodeStream("do not duplicate", CodeGenTypeEnum.VUE_PROJECT, APP_ID)
                .collectList()
                .block());

        verify(serviceFactory, times(1))
                .getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE_PROJECT, null);
    }

    @Test
    void approvalSignalMustEscapeWithoutRetryOrGenericStreamWrapping() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService generatorService = mock(AiCodeGeneratorService.class);
        GenerationApprovalRequiredException required = new GenerationApprovalRequiredException(
                "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK,
                "a".repeat(64), java.util.Map.of("snapshotName", "safe"));
        TestTokenStream stream = new TestTokenStream(tokenStream -> {
            tokenStream.emitIntermediateResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.builder()
                            .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                                    .id("call-1")
                                    .name("manageSnapshot")
                                    .arguments("{}")
                                    .build()))
                            .build())
                    .build());
            tokenStream.emitError(required);
        });
        when(serviceFactory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE_PROJECT, null))
                .thenReturn(generatorService);
        when(generatorService.generateVueProjectCodeStream(APP_ID, "approval flow"))
                .thenReturn(stream);

        GenerationApprovalRequiredException propagated = assertThrows(
                GenerationApprovalRequiredException.class,
                () -> facade(serviceFactory, mock(CodeFileSaverExecutor.class))
                        .generateAndSaveCodeStream(
                                "approval flow", CodeGenTypeEnum.VUE_PROJECT, APP_ID)
                        .collectList()
                        .block());

        assertSame(required, propagated);
        verify(serviceFactory, times(1))
                .getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE_PROJECT, null);
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
        serviceFactory.createTaskScopedAiCodeGeneratorService(
                APP_ID,
                CodeGenTypeEnum.HTML,
                null,
                Duration.ofSeconds(2),
                () -> { },
                () -> { }
        );
        generatorService.generateHtmlCodeStream(
                "正常慢流",
                InvocationParameters.from(
                        GenerationModelCancellationScope.INVOCATION_PARAMETER,
                        new GenerationModelCancellationScope()
                )
        );
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

    @Test
    void managedModelWithoutFirstSignalMustFailBeforeTheTurnWallClockDeadline() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService generatorService = mock(AiCodeGeneratorService.class);
        when(serviceFactory.createTaskScopedAiCodeGeneratorService(
                eq(APP_ID), eq(CodeGenTypeEnum.VUE_PROJECT), isNull(), any(Duration.class),
                any(Runnable.class), any(Runnable.class)))
                .thenReturn(generatorService);
        when(generatorService.generateVueProjectCodeStream(APP_ID, "首信号超时"))
                .thenReturn(new TestTokenStream(ignored -> { }));

        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 3);
        }
        budgets.put(GenerationBudgetKind.ROOT_MODEL_ATTEMPT, 1);
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-first-signal-timeout",
                APP_ID,
                7L,
                Instant.now(),
                new GenerationExecutionLimits(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(1),
                        budgets
                ),
                Clock.systemUTC()
        );
        AiModelRuntimeProperties runtimeProperties = retryProperties(Duration.ofMillis(1));
        runtimeProperties.setFirstSignalTimeout(Duration.ofMillis(40));
        long startedAt = System.nanoTime();

        RuntimeException failure = assertThrows(RuntimeException.class, () -> facade(
                serviceFactory,
                mock(CodeFileSaverExecutor.class),
                new GenerationPerformanceMonitorService(),
                runtimeProperties,
                new AiModelMetricsCollector(new SimpleMeterRegistry())
        ).generateAndSaveCodeStream(
                        "首信号超时",
                        CodeGenTypeEnum.VUE_PROJECT,
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
        assertEquals("first-signal", timeout.timeoutKind());
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofMillis(800)) < 0);
        verify(serviceFactory, times(1)).createTaskScopedAiCodeGeneratorService(
                eq(APP_ID), eq(CodeGenTypeEnum.VUE_PROJECT), isNull(), any(Duration.class),
                any(Runnable.class), any(Runnable.class));
    }

    @Test
    void streamedToolArgumentsMustCountAsFirstModelActivityWithoutBecomingVisibleOutput() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService generatorService = mock(AiCodeGeneratorService.class);
        when(serviceFactory.createTaskScopedAiCodeGeneratorService(
                eq(APP_ID), eq(CodeGenTypeEnum.VUE_PROJECT), isNull(), any(Duration.class),
                any(Runnable.class), any(Runnable.class)))
                .thenReturn(generatorService);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        TestTokenStream tokenStream = new TestTokenStream(stream -> {
            stream.emitPartialToolCall(PartialToolCall.builder()
                    .index(0)
                    .id("tool-call-1")
                    .name("writeFile")
                    .partialArguments("{\"path\":")
                    .build(), new TestStreamingHandle());
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(120);
                    stream.emitCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("完成"))
                            .build());
                } catch (Throwable failure) {
                    callbackFailure.set(failure);
                }
            });
        });
        when(generatorService.generateVueProjectCodeStream(APP_ID, "工具参数流"))
                .thenReturn(tokenStream);

        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 3);
        }
        budgets.put(GenerationBudgetKind.ROOT_MODEL_ATTEMPT, 1);
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-tool-activity",
                APP_ID,
                7L,
                Instant.now(),
                new GenerationExecutionLimits(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(1),
                        budgets
                ),
                Clock.systemUTC()
        );
        AiModelRuntimeProperties runtimeProperties = retryProperties(Duration.ofMillis(1));
        runtimeProperties.setFirstSignalTimeout(Duration.ofMillis(40));

        List<GenerationStreamEvent> events = facade(
                serviceFactory,
                mock(CodeFileSaverExecutor.class),
                new GenerationPerformanceMonitorService(),
                runtimeProperties,
                new AiModelMetricsCollector(new SimpleMeterRegistry())
        ).generateAndSaveCodeStream(
                        "工具参数流",
                        CodeGenTypeEnum.VUE_PROJECT,
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
                GenerationStreamEvent.GENERATION_STAGE.equals(event.getType())));
        assertNull(callbackFailure.get());
        verify(serviceFactory, times(1)).createTaskScopedAiCodeGeneratorService(
                eq(APP_ID), eq(CodeGenTypeEnum.VUE_PROJECT), isNull(), any(Duration.class),
                any(Runnable.class), any(Runnable.class));
    }

    @Test
    void managedToolLoopMustReserveCompletionWindowAndKeepSuccessfulWorkspaceMutation() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService generatorService = mock(AiCodeGeneratorService.class);
        AtomicReference<Runnable> beforeModelTurn = new AtomicReference<>();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-24T00:00:00Z"));
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-model-window",
                APP_ID,
                7L,
                clock.instant(),
                limits(Duration.ofSeconds(20)),
                clock
        );
        ToolExecutionRequest writeRequest = ToolExecutionRequest.builder()
                .id("write-1")
                .name("writeFile")
                .arguments("{\"path\":\"src/App.vue\"}")
                .build();
        TestTokenStream tokenStream = new TestTokenStream(stream -> {
            beforeModelTurn.get().run();
            context.consume(GenerationBudgetKind.TOOL_WRITE);
            context.recordSuccessfulWorkspaceMutations(1);
            stream.emitIntermediateResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.builder()
                            .toolExecutionRequests(List.of(writeRequest))
                            .build())
                    .build());
            stream.emitToolExecuted(ToolExecution.builder()
                    .request(writeRequest)
                    .result(ToolExecutionResult.builder().resultText("写入成功").build())
                    .invocationContext(mock(dev.langchain4j.invocation.InvocationContext.class))
                    .build());
            clock.advance(Duration.ofSeconds(16));
            try {
                beforeModelTurn.get().run();
                throw new AssertionError("完成窗口不足时不应继续启动模型回合");
            } catch (GenerationModelTurnAdmissionException admission) {
                stream.emitError(admission);
            }
        });
        when(serviceFactory.createTaskScopedAiCodeGeneratorService(
                eq(APP_ID), eq(CodeGenTypeEnum.VUE_PROJECT), isNull(), any(Duration.class),
                any(Runnable.class), any(Runnable.class))).thenAnswer(invocation -> {
            beforeModelTurn.set(invocation.getArgument(4));
            return generatorService;
        });
        when(generatorService.generateVueProjectCodeStream(APP_ID, "bounded tool loop"))
                .thenReturn(tokenStream);
        GenerationPerformanceMonitorService performanceMonitorService =
                new GenerationPerformanceMonitorService();
        GenerationStageAdmissionProperties properties = new GenerationStageAdmissionProperties();
        properties.setModelTurnMinimum(Duration.ofSeconds(1));
        properties.setModelHandoffReserve(Duration.ofSeconds(1));
        properties.setRepairModelMinimum(Duration.ofSeconds(1));
        properties.setBuildMinimum(Duration.ofSeconds(1));
        properties.setRuntimeValidationMinimum(Duration.ofSeconds(1));
        properties.setTerminalizationReserve(Duration.ofSeconds(1));
        GenerationStageAdmissionService stageAdmissionService = new GenerationStageAdmissionService(
                properties,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                performanceMonitorService
        );

        List<GenerationStreamEvent> events = facade(
                serviceFactory,
                mock(CodeFileSaverExecutor.class),
                performanceMonitorService,
                retryProperties(Duration.ofMillis(1)),
                new AiModelMetricsCollector(new SimpleMeterRegistry()),
                stageAdmissionService
        ).generateAndSaveCodeStream(
                        "bounded tool loop",
                        CodeGenTypeEnum.VUE_PROJECT,
                        APP_ID,
                        () -> false,
                        ignored -> { },
                        null,
                        context
                )
                .collectList()
                .block();

        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.AGENT_EVENT.equals(event.getType())
                        && "reserved_completion".equals(event.getData().get("status"))));
        assertTrue(events.stream().anyMatch(event ->
                GenerationStreamEvent.GENERATION_STAGE.equals(event.getType())
                        && "codegen_done".equals(event.getData().get("stage"))));
        assertEquals(1, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(1, context.used(GenerationBudgetKind.MODEL_TURN));
        assertEquals(1, context.used(GenerationBudgetKind.TOOL_WRITE));
        verify(serviceFactory, times(1)).createTaskScopedAiCodeGeneratorService(
                eq(APP_ID), eq(CodeGenTypeEnum.VUE_PROJECT), isNull(), any(Duration.class),
                any(Runnable.class), any(Runnable.class));
    }

    @Test
    void completionWindowMustNotTreatRejectedToolWriteAsSuccessfulGeneration() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService generatorService = mock(AiCodeGeneratorService.class);
        AtomicReference<Runnable> beforeModelTurn = new AtomicReference<>();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-24T00:00:00Z"));
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-model-window-rejected-write",
                APP_ID,
                7L,
                clock.instant(),
                limits(Duration.ofSeconds(20)),
                clock
        );
        ToolExecutionRequest writeRequest = ToolExecutionRequest.builder()
                .id("write-rejected")
                .name("writeFile")
                .arguments("{\"path\":\"src/App.vue\"}")
                .build();
        TestTokenStream tokenStream = new TestTokenStream(stream -> {
            beforeModelTurn.get().run();
            context.consume(GenerationBudgetKind.TOOL_WRITE);
            stream.emitIntermediateResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.builder()
                            .toolExecutionRequests(List.of(writeRequest))
                            .build())
                    .build());
            stream.emitToolExecuted(ToolExecution.builder()
                    .request(writeRequest)
                    .result(ToolExecutionResult.builder()
                            .resultText("文件写入失败：补丁被变更计划拒绝")
                            .build())
                    .invocationContext(mock(dev.langchain4j.invocation.InvocationContext.class))
                    .build());
            clock.advance(Duration.ofSeconds(16));
            try {
                beforeModelTurn.get().run();
                throw new AssertionError("完成窗口不足时不应继续启动模型回合");
            } catch (GenerationModelTurnAdmissionException admission) {
                stream.emitError(admission);
            }
        });
        when(serviceFactory.createTaskScopedAiCodeGeneratorService(
                eq(APP_ID), eq(CodeGenTypeEnum.VUE_PROJECT), isNull(), any(Duration.class),
                any(Runnable.class), any(Runnable.class))).thenAnswer(invocation -> {
            beforeModelTurn.set(invocation.getArgument(4));
            return generatorService;
        });
        when(generatorService.generateVueProjectCodeStream(APP_ID, "rejected tool write"))
                .thenReturn(tokenStream);
        GenerationPerformanceMonitorService performanceMonitorService =
                new GenerationPerformanceMonitorService();
        GenerationStageAdmissionProperties properties = new GenerationStageAdmissionProperties();
        properties.setModelTurnMinimum(Duration.ofSeconds(1));
        properties.setModelHandoffReserve(Duration.ofSeconds(1));
        properties.setRepairModelMinimum(Duration.ofSeconds(1));
        properties.setBuildMinimum(Duration.ofSeconds(1));
        properties.setRuntimeValidationMinimum(Duration.ofSeconds(1));
        properties.setTerminalizationReserve(Duration.ofSeconds(1));
        GenerationStageAdmissionService stageAdmissionService = new GenerationStageAdmissionService(
                properties,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                performanceMonitorService
        );
        List<GenerationStreamEvent> observedEvents = new ArrayList<>();

        assertThrows(GenerationModelTurnAdmissionException.class, () -> facade(
                serviceFactory,
                mock(CodeFileSaverExecutor.class),
                performanceMonitorService,
                retryProperties(Duration.ofMillis(1)),
                new AiModelMetricsCollector(new SimpleMeterRegistry()),
                stageAdmissionService
        ).generateAndSaveCodeStream(
                        "rejected tool write",
                        CodeGenTypeEnum.VUE_PROJECT,
                        APP_ID,
                        () -> false,
                        ignored -> { },
                        null,
                        context
                )
                .doOnNext(observedEvents::add)
                .collectList()
                .block());

        assertTrue(observedEvents.stream().anyMatch(event ->
                GenerationStreamEvent.TOOL_RESULT.equals(event.getType())));
        assertFalse(observedEvents.stream().anyMatch(event ->
                GenerationStreamEvent.GENERATION_STAGE.equals(event.getType())
                        && "codegen_done".equals(event.getData().get("stage"))));
        assertEquals(1, context.used(GenerationBudgetKind.TOOL_WRITE));
        assertEquals(0, context.successfulWorkspaceMutationCount());
    }

    @Test
    void managedStreamMustRecordTimeToFirstModelSignal() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService generatorService = mock(AiCodeGeneratorService.class);
        TestTokenStream tokenStream = new TestTokenStream(stream ->
                stream.emitPartialResponse("first", new TestStreamingHandle()));
        when(serviceFactory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE_PROJECT, null))
                .thenReturn(generatorService);
        when(generatorService.generateVueProjectCodeStream(APP_ID, "measure first signal"))
                .thenReturn(tokenStream);
        when(serviceFactory.createTaskScopedAiCodeGeneratorService(
                eq(APP_ID), eq(CodeGenTypeEnum.VUE_PROJECT), isNull(), any(Duration.class),
                any(Runnable.class), any(Runnable.class)))
                .thenReturn(generatorService);
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-first-signal",
                APP_ID,
                7L,
                Instant.now(),
                limits(Duration.ofSeconds(2)),
                Clock.systemUTC()
        );
        GenerationPerformanceMonitorService performanceMonitorService = new GenerationPerformanceMonitorService();
        performanceMonitorService.startTask(
                "task-first-signal", APP_ID, 7L, "heavy_generation", "vue_project");

        GenerationStreamEvent event = facade(
                serviceFactory, mock(CodeFileSaverExecutor.class), performanceMonitorService)
                .generateAndSaveCodeStream(
                        "measure first signal",
                        CodeGenTypeEnum.VUE_PROJECT,
                        APP_ID,
                        () -> false,
                        ignored -> { },
                        null,
                        context
                )
                .blockFirst();

        assertNotNull(event);
        assertEquals(1, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertTrue(performanceMonitorService.getSummary(10).getRecentTasks().getFirst()
                .getFirstTokenLatencyMs() > 0);
        assertTrue(performanceMonitorService.getSummary(10).getRecentTasks().getFirst().getSpans().stream()
                .anyMatch(span -> "model_time_to_first_signal".equals(span.getStage())));
    }

    @Test
    void managedRetryMustRecordAttemptsBackoffAndRecovery() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService primaryService = mock(AiCodeGeneratorService.class);
        AiCodeGeneratorService fallbackService = mock(AiCodeGeneratorService.class);
        TestTokenStream primaryStream = new TestTokenStream(stream ->
                stream.emitError(new dev.langchain4j.exception.TimeoutException("upstream timeout")));
        TestTokenStream fallbackStream = new TestTokenStream(stream ->
                stream.emitCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from("done"))
                        .build()));
        when(serviceFactory.createTaskScopedAiCodeGeneratorService(
                eq(APP_ID), eq(CodeGenTypeEnum.VUE_PROJECT), isNull(), any(Duration.class),
                any(Runnable.class), any(Runnable.class)))
                .thenReturn(primaryService, fallbackService);
        when(primaryService.generateVueProjectCodeStream(APP_ID, "managed retry"))
                .thenReturn(primaryStream);
        when(fallbackService.generateVueProjectCodeStream(APP_ID, "managed retry"))
                .thenReturn(fallbackStream);

        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-root-retry",
                APP_ID,
                7L,
                Instant.now(),
                limits(Duration.ofSeconds(2)),
                Clock.systemUTC()
        );
        GenerationPerformanceMonitorService performanceMonitorService =
                new GenerationPerformanceMonitorService();
        performanceMonitorService.startTask(
                "task-root-retry", APP_ID, 7L, "heavy_generation", "vue_project");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiModelRuntimeProperties properties = retryProperties(Duration.ofMillis(1));

        List<GenerationStreamEvent> events = facade(
                serviceFactory,
                mock(CodeFileSaverExecutor.class),
                performanceMonitorService,
                properties,
                new AiModelMetricsCollector(registry)
        ).generateAndSaveCodeStream(
                        "managed retry",
                        CodeGenTypeEnum.VUE_PROJECT,
                        APP_ID,
                        () -> false,
                        ignored -> { },
                        null,
                        context
                )
                .collectList()
                .block();

        assertNotNull(events);
        assertEquals(2, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(1, registry.find("ai_model_root_retries_total")
                .tag("outcome", "scheduled")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("ai_model_root_retries_total")
                .tag("outcome", "recovered")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("ai_model_root_attempts_total")
                .tag("outcome", "failed")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("ai_model_root_attempts_total")
                .tag("outcome", "success")
                .counter()
                .count(), 0.001);
        assertEquals(2, performanceMonitorService.getSummary(10).getRecentTasks().getFirst()
                .getSpans().stream()
                .filter(span -> "root_model_attempt".equals(span.getStage()))
                .count());
        assertTrue(performanceMonitorService.getSummary(10).getRecentTasks().getFirst()
                .getSpans().stream()
                .anyMatch(span -> "root_model_retry_backoff".equals(span.getStage())));
    }

    @Test
    void managedRetryMustStopWhenDeadlineCannotAffordTheMinimumBackoff() {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService generatorService = mock(AiCodeGeneratorService.class);
        TestTokenStream failedStream = new TestTokenStream(stream ->
                stream.emitError(new dev.langchain4j.exception.TimeoutException("upstream timeout")));
        when(serviceFactory.createTaskScopedAiCodeGeneratorService(
                eq(APP_ID), eq(CodeGenTypeEnum.VUE_PROJECT), isNull(), any(Duration.class),
                any(Runnable.class), any(Runnable.class)))
                .thenReturn(generatorService);
        when(generatorService.generateVueProjectCodeStream(APP_ID, "deadline retry"))
                .thenReturn(failedStream);

        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 3);
        }
        GenerationExecutionLimits limits = new GenerationExecutionLimits(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofMillis(900),
                budgets
        );
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-root-retry-deadline",
                APP_ID,
                7L,
                Instant.now(),
                limits,
                Clock.systemUTC()
        );
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThrows(RuntimeException.class, () -> facade(
                serviceFactory,
                mock(CodeFileSaverExecutor.class),
                new GenerationPerformanceMonitorService(),
                retryProperties(Duration.ofMillis(200)),
                new AiModelMetricsCollector(registry)
        ).generateAndSaveCodeStream(
                        "deadline retry",
                        CodeGenTypeEnum.VUE_PROJECT,
                        APP_ID,
                        () -> false,
                        ignored -> { },
                        null,
                        context
                )
                .collectList()
                .block());

        verify(serviceFactory, times(1)).createTaskScopedAiCodeGeneratorService(
                eq(APP_ID), eq(CodeGenTypeEnum.VUE_PROJECT), isNull(), any(Duration.class),
                any(Runnable.class), any(Runnable.class));
        assertEquals(1, registry.find("ai_model_root_retries_total")
                .tag("outcome", "skipped_deadline")
                .counter()
                .count(), 0.001);
    }

    private GenerationExecutionLimits limits(Duration timeout) {
        return limits(timeout, timeout);
    }

    private GenerationExecutionLimits limits(Duration taskTimeout, Duration modelCallTimeout) {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 3);
        }
        return new GenerationExecutionLimits(
                taskTimeout, modelCallTimeout, Duration.ofMillis(1), budgets);
    }

    private AiCodeGeneratorFacade facade(TokenStream tokenStream) {
        AiCodeGeneratorServiceFactory serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        AiCodeGeneratorService generatorService = mock(AiCodeGeneratorService.class);
        when(serviceFactory.getAiCodeGeneratorService(APP_ID, CodeGenTypeEnum.VUE_PROJECT, null))
                .thenReturn(generatorService);
        when(generatorService.generateVueProjectCodeStream(APP_ID, "build a page"))
                .thenReturn(tokenStream);
        return facade(serviceFactory, mock(CodeFileSaverExecutor.class));
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
        return facade(
                serviceFactory,
                saverExecutor,
                performanceMonitorService,
                runtimeProperties,
                metricsCollector,
                stageAdmissionService(performanceMonitorService)
        );
    }

    private AiCodeGeneratorFacade facade(
            AiCodeGeneratorServiceFactory serviceFactory,
            CodeFileSaverExecutor saverExecutor,
            GenerationPerformanceMonitorService performanceMonitorService,
            AiModelRuntimeProperties runtimeProperties,
            AiModelMetricsCollector metricsCollector,
            GenerationStageAdmissionService stageAdmissionService
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
                saverExecutor,
                workspaceService,
                performanceMonitorService,
                runtimeProperties,
                metricsCollector,
                stageAdmissionService
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

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }

    private static final class TestTokenStream implements TokenStream {

        private final Consumer<TestTokenStream> startAction;
        private BiConsumer<PartialResponse, PartialResponseContext> partialResponseConsumer;
        private BiConsumer<PartialThinking, PartialThinkingContext> partialThinkingConsumer;
        private BiConsumer<PartialToolCall, PartialToolCallContext> partialToolCallConsumer;
        private Consumer<ChatResponse> intermediateResponseConsumer;
        private Consumer<ToolExecution> toolExecutionConsumer;
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
            this.partialThinkingConsumer = consumer;
            return this;
        }

        @Override
        public TokenStream onPartialToolCallWithContext(
                BiConsumer<PartialToolCall, PartialToolCallContext> consumer) {
            this.partialToolCallConsumer = consumer;
            return this;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<Content>> consumer) {
            return this;
        }

        @Override
        public TokenStream onIntermediateResponse(Consumer<ChatResponse> consumer) {
            this.intermediateResponseConsumer = consumer;
            return this;
        }

        @Override
        public TokenStream onToolExecuted(Consumer<ToolExecution> consumer) {
            this.toolExecutionConsumer = consumer;
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
            partialResponseConsumer.accept(new PartialResponse(text), new PartialResponseContext(handle));
        }

        private void emitPartialThinking(String text, StreamingHandle handle) {
            partialThinkingConsumer.accept(new PartialThinking(text), new PartialThinkingContext(handle));
        }

        private void emitPartialToolCall(PartialToolCall partialToolCall, StreamingHandle handle) {
            partialToolCallConsumer.accept(partialToolCall, new PartialToolCallContext(handle));
        }

        private void emitIntermediateResponse(ChatResponse response) {
            intermediateResponseConsumer.accept(response);
        }

        private void emitToolExecuted(ToolExecution toolExecution) {
            toolExecutionConsumer.accept(toolExecution);
        }

        private void emitCompleteResponse(ChatResponse response) {
            completeResponseConsumer.accept(response);
        }

        private void emitError(Throwable failure) {
            errorConsumer.accept(failure);
        }
    }
}
