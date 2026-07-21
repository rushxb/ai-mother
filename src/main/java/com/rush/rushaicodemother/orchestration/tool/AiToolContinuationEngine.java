package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.progress.ReasoningProgressTracker;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationStreamingModelCallSupervisor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolService;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Continues the existing model/tool conversation after a durable human decision. */
@Component
public class AiToolContinuationEngine {

    private static final int HEAVY_CHAT_MEMORY_MESSAGES = 12;

    private final ChatMemoryStore chatMemoryStore;
    private final ToolManager toolManager;
    private final StreamingModelFactory streamingModelFactory;
    private final ToolExecutionFailurePolicy toolExecutionFailurePolicy;
    private final ToolApprovalService toolApprovalService;
    private final GenerationToolExecutionContextService toolExecutionContextService;
    private final GenerationPerformanceMonitorService performanceMonitorService;
    private final AiToolInvocationPolicy aiToolInvocationPolicy;
    private final DurableToolConversationCodec conversationCodec;
    private final GenerationStreamingModelCallSupervisor modelCallSupervisor;

    @Autowired
    public AiToolContinuationEngine(ChatMemoryStore chatMemoryStore,
                                    ToolManager toolManager,
                                    StreamingModelFactory streamingModelFactory,
                                    ToolExecutionFailurePolicy toolExecutionFailurePolicy,
                                    ToolApprovalService toolApprovalService,
                                    GenerationToolExecutionContextService toolExecutionContextService,
                                    GenerationPerformanceMonitorService performanceMonitorService,
                                    AiToolInvocationPolicy aiToolInvocationPolicy,
                                    DurableToolConversationCodec conversationCodec,
                                    GenerationStreamingModelCallSupervisor modelCallSupervisor) {
        this.chatMemoryStore = chatMemoryStore;
        this.toolManager = toolManager;
        this.streamingModelFactory = streamingModelFactory;
        this.toolExecutionFailurePolicy = toolExecutionFailurePolicy;
        this.toolApprovalService = toolApprovalService;
        this.toolExecutionContextService = toolExecutionContextService;
        this.performanceMonitorService = performanceMonitorService;
        this.aiToolInvocationPolicy = aiToolInvocationPolicy;
        this.conversationCodec = conversationCodec;
        this.modelCallSupervisor = modelCallSupervisor;
    }

    AiToolContinuationEngine(ChatMemoryStore chatMemoryStore,
                             ToolManager toolManager,
                             StreamingModelFactory streamingModelFactory,
                             ToolExecutionFailurePolicy toolExecutionFailurePolicy,
                              ToolApprovalService toolApprovalService,
                              GenerationToolExecutionContextService toolExecutionContextService,
                              AiToolInvocationPolicy aiToolInvocationPolicy,
                              GenerationStreamingModelCallSupervisor modelCallSupervisor) {
        this(chatMemoryStore, toolManager, streamingModelFactory, toolExecutionFailurePolicy,
                toolApprovalService, toolExecutionContextService,
                new GenerationPerformanceMonitorService(List.of()), aiToolInvocationPolicy,
                new DurableToolConversationCodec(),
                modelCallSupervisor);
    }

    public Flux<GenerationStreamEvent> continueAfterDecision(
            ToolApprovalRecord approval,
            GenerationToolContinuationState state,
            GenerationExecutionContext executionContext,
            BooleanSupplier cancelChecker,
            Consumer<GenerationCancellationHandle> cancellationHandleConsumer) {
        requireContinuation(approval, state, executionContext);
        BooleanSupplier safeCancelChecker = cancelChecker == null ? () -> false : cancelChecker;
        Consumer<GenerationCancellationHandle> safeHandleConsumer = cancellationHandleConsumer == null
                ? ignored -> { }
                : cancellationHandleConsumer;
        return Flux.create(sink -> start(
                approval, state, executionContext, safeCancelChecker, safeHandleConsumer, sink));
    }

    private void start(ToolApprovalRecord approval,
                       GenerationToolContinuationState state,
                       GenerationExecutionContext executionContext,
                       BooleanSupplier cancelChecker,
                       Consumer<GenerationCancellationHandle> cancellationHandleConsumer,
                       FluxSink<GenerationStreamEvent> sink) {
        if (cancelChecker.getAsBoolean()) {
            sink.complete();
            return;
        }
        ContinuationMemory memory = restoreMemory(state, approval.invocationCheckpoint());
        ToolService toolService = new ToolService();
        toolService.tools(List.of((Object[]) toolManager.getToolsForCodeGen(state.codeGenType())));
        toolService.beforeToolExecution(event -> aiToolInvocationPolicy.authorize(
                event, state.codeGenType(), state.performanceProfile()));
        toolService.afterToolExecution(toolExecution -> aiToolInvocationPolicy.clearActiveInvocation());
        toolService.maxToolCallingRoundTrips(state.performanceProfile() == null
                ? 10
                : state.performanceProfile().maxToolInvocations());
        toolService.executionErrorHandler((failure, context) -> toolExecutionFailurePolicy.handle(
                failure, context, state.codeGenType(), state.performanceProfile()));
        InvocationContext invocationContext = invocationContext(
                state, memory.messages(), executionContext.executionFence());
        try {
            resolveInterruptedToolRound(
                    approval, state, memory, toolService, invocationContext, cancelChecker, sink);
            StreamingChatModel model = streamingModelFactory.createExecutionModel(
                    state.performanceProfile(),
                    executionContext.limits().modelCallTimeout(),
                    () -> executionContext.consume(GenerationBudgetKind.MODEL_ATTEMPT));
            AtomicReference<GenerationCancellationHandle> activeCall = new AtomicReference<>();
            Consumer<GenerationCancellationHandle> registerCancellation = handle -> {
                activeCall.set(handle);
                cancellationHandleConsumer.accept(handle);
            };
            sink.onCancel(() -> cancel(activeCall.get()));
            requestNextModelTurn(
                    model, memory, toolService, invocationContext,
                    state, executionContext, cancelChecker, registerCancellation,
                    sink,
                    state.performanceProfile() == null
                            ? 10
                            : state.performanceProfile().maxToolInvocations());
        } catch (RuntimeException failure) {
            sink.error(failure);
        }
    }

    private void resolveInterruptedToolRound(ToolApprovalRecord approval,
                                             GenerationToolContinuationState state,
                                             ContinuationMemory memory,
                                             ToolService toolService,
                                             InvocationContext invocationContext,
                                             BooleanSupplier cancelChecker,
                                             FluxSink<GenerationStreamEvent> sink) {
        ToolInvocationCheckpoint checkpoint = approval.invocationCheckpoint();
        List<ChatMessage> messages = memory.messages();
        int assistantIndex = findInterruptedAssistantMessage(messages, checkpoint);
        AiMessage assistantMessage = (AiMessage) messages.get(assistantIndex);
        Set<String> completedRequestIds = completedRequestIds(messages, assistantIndex + 1);
        boolean checkpointResolved = completedRequestIds.contains(checkpoint.requestId());
        for (ToolExecutionRequest request : assistantMessage.toolExecutionRequests()) {
            if (cancelChecker.getAsBoolean()) {
                return;
            }
            if (completedRequestIds.contains(request.id())) {
                continue;
            }
            ToolExecutionResult result;
            if (Objects.equals(request.id(), checkpoint.requestId())) {
                checkpointResolved = true;
                result = executeToolWithSpan(state, request,
                        () -> decisionResult(approval, toolService, invocationContext, request));
            } else {
                result = executeToolWithSpan(state, request, () -> toolService.executeTool(
                        invocationContext, toolService.toolExecutors(), request, null, null));
            }
            memory.add(toResultMessage(request, result));
            sink.next(toolResultEvent(request, result));
        }
        if (!checkpointResolved) {
            throw new IllegalStateException("pending tool invocation is missing from chat memory");
        }
    }

    private ToolExecutionResult decisionResult(ToolApprovalRecord approval,
                                               ToolService toolService,
                                               InvocationContext invocationContext,
                                               ToolExecutionRequest request) {
        if (approval.status() == ToolApprovalStatus.APPROVED
                || approval.status() == ToolApprovalStatus.CONSUMED) {
            return executeApprovedInvocation(approval, toolService, invocationContext, request);
        }
        if (approval.status() == ToolApprovalStatus.REJECTED) {
            return ToolExecutionResult.builder()
                    .isError(true)
                    .resultText("The project owner rejected this destructive tool action. Choose a safe alternative.")
                    .build();
        }
        if (approval.status() == ToolApprovalStatus.EXPIRED) {
            return ToolExecutionResult.builder()
                    .isError(true)
                    .resultText("The destructive tool approval expired. Choose a safe alternative.")
                    .build();
        }
        throw new IllegalStateException("tool approval decision is not resumable: " + approval.status());
    }

    private ToolExecutionResult executeApprovedInvocation(
            ToolApprovalRecord approval,
            ToolService toolService,
            InvocationContext invocationContext,
            ToolExecutionRequest request
    ) {
        ToolApprovalRecord execution = toolApprovalService.beginExecution(approval);
        if (execution.status() == ToolApprovalStatus.CONSUMED) {
            return replay(execution);
        }
        ToolInvocationCheckpoint checkpoint = execution.invocationCheckpoint();
        GenerationToolExecutionContextService.ToolInvocationExecution invocation =
                new GenerationToolExecutionContextService.ToolInvocationExecution(
                        execution.taskId(), checkpoint.requestId(), checkpoint.toolName(),
                        checkpoint.argumentsDigest());
        ToolExecutionResult result = toolExecutionContextService.withInvocation(
                invocation,
                () -> toolService.executeTool(
                        invocationContext, toolService.toolExecutors(), request, null, null)
        );
        ToolApprovalRecord completed = toolApprovalService.completeExecution(
                execution,
                new ToolExecutionOutcome(result.isError(), result.resultText())
        );
        return replay(completed);
    }

    private ToolExecutionResult replay(ToolApprovalRecord approval) {
        ToolExecutionOutcome outcome = approval.executionOutcome();
        if (outcome == null) {
            throw new IllegalStateException("consumed tool invocation result is missing");
        }
        return ToolExecutionResult.builder()
                .isError(outcome.error())
                .resultText(outcome.resultText())
                .build();
    }

    private void requestNextModelTurn(StreamingChatModel model,
                                      ContinuationMemory memory,
                                      ToolService toolService,
                                      InvocationContext invocationContext,
                                      GenerationToolContinuationState state,
                                      GenerationExecutionContext executionContext,
                                      BooleanSupplier cancelChecker,
                                      Consumer<GenerationCancellationHandle> cancellationHandleConsumer,
                                      FluxSink<GenerationStreamEvent> sink,
                                      int remainingToolRounds) {
        if (cancelChecker.getAsBoolean() || sink.isCancelled()) {
            sink.complete();
            return;
        }
        if (remainingToolRounds <= 0) {
            sink.error(new IllegalStateException("tool continuation round budget exhausted"));
            return;
        }
        ChatRequest request = ChatRequest.builder()
                .messages(memory.messages())
                .toolSpecifications(toolService.toolSpecifications())
                .build();
        ReasoningProgressTracker reasoningProgress = new ReasoningProgressTracker(state.taskId());
        modelCallSupervisor.chat(
                model,
                request,
                executionContext,
                cancelChecker,
                cancellationHandleConsumer,
                new StreamingChatResponseHandler() {
                    private final AtomicBoolean terminated = new AtomicBoolean();

            @Override
            public void onPartialResponse(PartialResponse partialResponse,
                                          PartialResponseContext context) {
                if (!cancelChecker.getAsBoolean() && !sink.isCancelled()) {
                    reasoningProgress.completeIfStarted().ifPresent(sink::next);
                    sink.next(GenerationStreamEvent.aiDelta(partialResponse.text()));
                }
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking,
                                          PartialThinkingContext context) {
                if (!cancelChecker.getAsBoolean() && !sink.isCancelled()) {
                    reasoningProgress.startIfNeeded().ifPresent(sink::next);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                if (!terminated.compareAndSet(false, true) || sink.isCancelled()) {
                    return;
                }
                try {
                    reasoningProgress.completeIfStarted().ifPresent(sink::next);
                    AiMessage aiMessage = response.aiMessage();
                    memory.add(aiMessage);
                    if (!aiMessage.hasToolExecutionRequests()) {
                        sink.next(GenerationStreamEvent.generationStage(
                                "代码生成完成",
                                Map.of(
                                        "status", "transition",
                                        "stage", "codegen_done",
                                        "summary", "Approved tool invocation completed and generation continued",
                                        "taskId", state.taskId()
                                )));
                        sink.complete();
                        return;
                    }
                    for (int index = 0; index < aiMessage.toolExecutionRequests().size(); index++) {
                        ToolExecutionRequest toolRequest = aiMessage.toolExecutionRequests().get(index);
                        sink.next(toolCallEvent(toolRequest, index));
                        ToolExecutionResult result = executeToolWithSpan(
                                state,
                                toolRequest,
                                () -> toolService.executeTool(
                                        invocationContext, toolService.toolExecutors(),
                                        toolRequest, null, null)
                        );
                        memory.add(toResultMessage(toolRequest, result));
                        sink.next(toolResultEvent(toolRequest, result));
                    }
                    requestNextModelTurn(
                            model, memory, toolService, invocationContext, state,
                            executionContext, cancelChecker, cancellationHandleConsumer,
                            sink, remainingToolRounds - 1);
                } catch (RuntimeException failure) {
                    sink.error(failure);
                }
            }

            @Override
            public void onError(Throwable error) {
                if (terminated.compareAndSet(false, true) && !sink.isCancelled()) {
                    reasoningProgress.failIfStarted().ifPresent(sink::next);
                    sink.error(error);
                }
            }

                });
    }

    private InvocationContext invocationContext(GenerationToolContinuationState state,
                                                List<ChatMessage> messages,
                                                com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence executionFence) {
        UserMessage userMessage = messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .reduce((left, right) -> right)
                .orElseGet(() -> UserMessage.from(state.userPrompt()));
        return InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .interfaceName(AiToolContinuationEngine.class.getName())
                .methodName("continueAfterDecision")
                .methodArguments(List.of(state.taskId()))
                .userMessage(userMessage)
                .chatMemoryId(state.appId())
                .invocationParameters(InvocationParameters.from(
                        GenerationToolExecutionContextService.EXECUTION_FENCE_PARAMETER,
                        executionFence))
                .timestampNow()
                .build();
    }

    private ContinuationMemory restoreMemory(GenerationToolContinuationState state,
                                             ToolInvocationCheckpoint checkpoint) {
        if (state.durableConversation() != null) {
            List<ChatMessage> messages = conversationCodec.restore(
                    state.durableConversation(), checkpoint);
            ChatMemory memory = MessageWindowChatMemory.withMaxMessages(HEAVY_CHAT_MEMORY_MESSAGES);
            messages.forEach(memory::add);
            ContinuationMemory restored = new ContinuationMemory(memory, state.appId(), true);
            restored.synchronize();
            return restored;
        }
        ChatMemory legacyMemory = MessageWindowChatMemory.builder()
                .id(state.appId())
                .chatMemoryStore(chatMemoryStore)
                .maxMessages(HEAVY_CHAT_MEMORY_MESSAGES)
                .build();
        return new ContinuationMemory(legacyMemory, state.appId(), false);
    }

    private int findInterruptedAssistantMessage(List<ChatMessage> messages,
                                                ToolInvocationCheckpoint checkpoint) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            if (!(message instanceof AiMessage aiMessage) || !aiMessage.hasToolExecutionRequests()) {
                continue;
            }
            boolean matches = aiMessage.toolExecutionRequests().stream().anyMatch(request ->
                    Objects.equals(request.id(), checkpoint.requestId())
                            && Objects.equals(request.name(), checkpoint.toolName())
                            && Objects.equals(request.arguments(), checkpoint.argumentsJson()));
            if (matches) {
                return index;
            }
        }
        throw new IllegalStateException("pending tool invocation does not match chat memory");
    }

    private Set<String> completedRequestIds(List<ChatMessage> messages, int startIndex) {
        Set<String> completed = new HashSet<>();
        for (int index = startIndex; index < messages.size(); index++) {
            if (messages.get(index) instanceof ToolExecutionResultMessage result && result.id() != null) {
                completed.add(result.id());
            }
        }
        return completed;
    }

    private ToolExecutionResultMessage toResultMessage(ToolExecutionRequest request,
                                                       ToolExecutionResult result) {
        return ToolExecutionResultMessage.builder()
                .id(request.id())
                .toolName(request.name())
                .contents(result.resultContents())
                .isError(result.isError())
                .attributes(result.attributes())
                .build();
    }

    private GenerationStreamEvent toolCallEvent(ToolExecutionRequest request, int index) {
        return GenerationStreamEvent.toolCall(
                Objects.toString(request.name(), ""),
                Map.of(
                        "toolName", Objects.toString(request.name(), ""),
                        "arguments", Objects.toString(request.arguments(), ""),
                        "requestId", Objects.toString(request.id(), ""),
                        "toolIndex", index
                ));
    }

    private GenerationStreamEvent toolResultEvent(ToolExecutionRequest request,
                                                   ToolExecutionResult result) {
        return GenerationStreamEvent.toolResult(
                Objects.toString(result.resultText(), ""),
                Map.of(
                        "toolName", Objects.toString(request.name(), ""),
                        "arguments", Objects.toString(request.arguments(), ""),
                        "requestId", Objects.toString(request.id(), ""),
                        "result", Objects.toString(result.resultText(), ""),
                        "isError", result.isError()
                ));
    }

    private void cancel(GenerationCancellationHandle handle) {
        if (handle != null) {
            handle.cancel();
        }
    }

    private ToolExecutionResult executeToolWithSpan(GenerationToolContinuationState state,
                                                    ToolExecutionRequest request,
                                                    Supplier<ToolExecutionResult> action) {
        String toolName = request == null || request.name() == null || request.name().isBlank()
                ? "unknown"
                : request.name().replaceAll("[^A-Za-z0-9_.-]", "_");
        GenerationPerformanceMonitorService.SpanTimer timer = performanceMonitorService.startSpan(
                state.taskId(), "tool_" + toolName, GenerationSpanCategory.TOOL);
        try {
            ToolExecutionResult result = action.get();
            if (result != null && result.isError()) {
                timer.close("failed", "tool_result_error");
            } else {
                timer.success();
            }
            return result;
        } catch (RuntimeException failure) {
            timer.failed(failure.getMessage());
            throw failure;
        }
    }

    private void requireContinuation(ToolApprovalRecord approval,
                                     GenerationToolContinuationState state,
                                     GenerationExecutionContext executionContext) {
        if (approval == null || approval.invocationCheckpoint() == null || state == null
                || executionContext == null
                || !Objects.equals(approval.taskId(), state.taskId())
                || !Objects.equals(approval.appId(), state.appId())
                || !Objects.equals(approval.userId(), state.userId())
                || !Objects.equals(state.taskId(), executionContext.taskId())
                || !Objects.equals(state.appId(), executionContext.appId())
                || !Objects.equals(state.userId(), executionContext.userId())
                || state.userPrompt() == null || state.userPrompt().isBlank()
                || (approval.status() != ToolApprovalStatus.APPROVED
                    && approval.status() != ToolApprovalStatus.CONSUMED
                    && approval.status() != ToolApprovalStatus.REJECTED
                    && approval.status() != ToolApprovalStatus.EXPIRED)) {
            throw new IllegalArgumentException("tool continuation decision and checkpoint are inconsistent");
        }
    }

    private final class ContinuationMemory {
        private final ChatMemory memory;
        private final Long memoryId;
        private final boolean checkpointBacked;

        private ContinuationMemory(ChatMemory memory,
                                   Long memoryId,
                                   boolean checkpointBacked) {
            this.memory = memory;
            this.memoryId = memoryId;
            this.checkpointBacked = checkpointBacked;
        }

        private List<ChatMessage> messages() {
            return memory.messages();
        }

        private void add(ChatMessage message) {
            memory.add(message);
            synchronize();
        }

        private void synchronize() {
            if (checkpointBacked) {
                chatMemoryStore.updateMessages(memoryId, memory.messages());
            }
        }
    }
}
