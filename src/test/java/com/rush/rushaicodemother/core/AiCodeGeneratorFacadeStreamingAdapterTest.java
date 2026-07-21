package com.rush.rushaicodemother.core;

import com.rush.rushaicodemother.ai.AiCodeGeneratorService;
import com.rush.rushaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.core.saver.CodeFileSaverExecutor;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.orchestration.tool.DestructiveToolAction;
import com.rush.rushaicodemother.orchestration.tool.GenerationApprovalRequiredException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
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
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        when(generatorService.generateHtmlCodeStream("build invalid html"))
                .thenReturn(Flux.just("internal-template-path=C:/secret/template"));
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
        when(generatorService.generateHtmlCodeStream("slow html"))
                .thenReturn(Flux.just("late").delayElements(Duration.ofMillis(200)));
        GenerationExecutionContext context = new GenerationExecutionContext(
                "task-model-timeout",
                APP_ID,
                7L,
                Instant.now(),
                limits(Duration.ofMillis(80)),
                Clock.systemUTC()
        );
        AiCodeGeneratorFacade facade = facade(serviceFactory, mock(CodeFileSaverExecutor.class));

        assertThrows(RuntimeException.class, () -> facade.generateAndSaveCodeStream(
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
    }

    private GenerationExecutionLimits limits(Duration timeout) {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 3);
        }
        return new GenerationExecutionLimits(timeout, timeout, Duration.ofMillis(1), budgets);
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
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        Path workspaceRoot = Path.of("target", "test-work", "facade", "vue_project_" + APP_ID)
                .toAbsolutePath()
                .normalize();
        GenerationWorkspace workspace = new GenerationWorkspace(
                APP_ID,
                CodeGenTypeEnum.VUE_PROJECT,
                workspaceRoot,
                workspaceRoot,
                false,
                workspaceRoot,
                null,
                Set.of(),
                Set.of()
        );
        when(workspaceService.resolve(APP_ID, CodeGenTypeEnum.VUE_PROJECT)).thenReturn(workspace);
        return new AiCodeGeneratorFacade(serviceFactory, saverExecutor, workspaceService);
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
        private BiConsumer<PartialThinking, PartialThinkingContext> partialThinkingConsumer;
        private BiConsumer<PartialToolCall, PartialToolCallContext> partialToolCallConsumer;
        private Consumer<ChatResponse> intermediateResponseConsumer;
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

        private void emitCompleteResponse(ChatResponse response) {
            completeResponseConsumer.accept(response);
        }

        private void emitError(Throwable failure) {
            errorConsumer.accept(failure);
        }
    }
}
