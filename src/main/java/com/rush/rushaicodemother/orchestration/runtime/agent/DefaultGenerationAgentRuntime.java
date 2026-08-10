package com.rush.rushaicodemother.orchestration.runtime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.config.ChatMemoryProperties;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationAgentCompletionPolicy;
import com.rush.rushaicodemother.orchestration.progress.ReasoningProgressTracker;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationModelTurnAdmissionException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionService;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationStreamingModelCallSupervisor;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCallTimeoutException;
import com.rush.rushaicodemother.orchestration.tool.AiToolInvocationPolicy;
import com.rush.rushaicodemother.orchestration.tool.CompletedToolCallContextCompactor;
import com.rush.rushaicodemother.orchestration.tool.DurableToolConversationCodec;
import com.rush.rushaicodemother.orchestration.tool.GenerationApprovalRequiredException;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolContinuationState;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalRecord;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalService;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalStatus;
import com.rush.rushaicodemother.orchestration.tool.ToolBatchExecutionPlanner;
import com.rush.rushaicodemother.orchestration.tool.ToolBatchExecutor;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionFailurePolicy;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionOutcome;
import com.rush.rushaicodemother.orchestration.tool.ToolInvocationCheckpoint;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ReturnBehavior;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

/** 首次生成与人工审批恢复共用的默认显式智能体运行时。 */
@Component
public class DefaultGenerationAgentRuntime implements GenerationAgentRuntime {

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
    private final CompletedToolCallContextCompactor completedToolCallContextCompactor;
    private final GenerationStageAdmissionService generationStageAdmissionService;
    private final GenerationAgentTurnPolicy generationAgentTurnPolicy;
    private final GenerationAgentConversationInitializer conversationInitializer;
    private final GenerationAgentCompletionPolicy generationAgentCompletionPolicy;
    private final ToolBatchExecutionPlanner toolBatchExecutionPlanner;
    private final ToolBatchExecutor toolBatchExecutor;

    /**
     * 创建默认智能体运行时，并注入模型调用、工具治理、审批恢复和短期记忆依赖。
     *
     * @param chatMemoryStore 对话记忆存储
     * @param toolManager 工具管理器
     * @param streamingModelFactory 流式模型工厂
     * @param toolExecutionFailurePolicy 工具执行失败策略
     * @param toolApprovalService 工具审批服务
     * @param toolExecutionContextService 工具执行上下文服务
     * @param performanceMonitorService 性能监控服务
     * @param aiToolInvocationPolicy 工具调用策略
     * @param conversationCodec 持久会话编解码器
     * @param modelCallSupervisor 模型调用监督器
     * @param completedToolCallContextCompactor 已完成工具上下文压缩器
     * @param generationStageAdmissionService 生成阶段准入服务
     * @param generationAgentTurnPolicy 智能体轮次策略
     * @param conversationInitializer 短期会话初始化器
     * @param toolBatchExecutionPlanner 工具批次分段规划器
     * @param toolBatchExecutor 工具批次执行器
     */
    public DefaultGenerationAgentRuntime(ChatMemoryStore chatMemoryStore,
                                         ToolManager toolManager,
                                         StreamingModelFactory streamingModelFactory,
                                         ToolExecutionFailurePolicy toolExecutionFailurePolicy,
                                         ToolApprovalService toolApprovalService,
                                         GenerationToolExecutionContextService toolExecutionContextService,
                                         GenerationPerformanceMonitorService performanceMonitorService,
                                         AiToolInvocationPolicy aiToolInvocationPolicy,
                                         DurableToolConversationCodec conversationCodec,
                                         GenerationStreamingModelCallSupervisor modelCallSupervisor,
                                         CompletedToolCallContextCompactor completedToolCallContextCompactor,
                                         GenerationStageAdmissionService generationStageAdmissionService,
                                         GenerationAgentTurnPolicy generationAgentTurnPolicy,
                                         GenerationAgentConversationInitializer conversationInitializer,
                                         ToolBatchExecutionPlanner toolBatchExecutionPlanner,
                                         ToolBatchExecutor toolBatchExecutor) {
        this(chatMemoryStore, toolManager, streamingModelFactory, toolExecutionFailurePolicy,
                toolApprovalService, toolExecutionContextService, performanceMonitorService,
                aiToolInvocationPolicy, conversationCodec, modelCallSupervisor,
                completedToolCallContextCompactor, generationStageAdmissionService,
                generationAgentTurnPolicy, conversationInitializer,
                new GenerationAgentCompletionPolicy(),
                toolBatchExecutionPlanner, toolBatchExecutor);
    }

    @Autowired
    public DefaultGenerationAgentRuntime(ChatMemoryStore chatMemoryStore,
                                         ToolManager toolManager,
                                         StreamingModelFactory streamingModelFactory,
                                         ToolExecutionFailurePolicy toolExecutionFailurePolicy,
                                         ToolApprovalService toolApprovalService,
                                         GenerationToolExecutionContextService toolExecutionContextService,
                                         GenerationPerformanceMonitorService performanceMonitorService,
                                         AiToolInvocationPolicy aiToolInvocationPolicy,
                                         DurableToolConversationCodec conversationCodec,
                                         GenerationStreamingModelCallSupervisor modelCallSupervisor,
                                         CompletedToolCallContextCompactor completedToolCallContextCompactor,
                                         GenerationStageAdmissionService generationStageAdmissionService,
                                         GenerationAgentTurnPolicy generationAgentTurnPolicy,
                                         GenerationAgentConversationInitializer conversationInitializer,
                                         GenerationAgentCompletionPolicy generationAgentCompletionPolicy,
                                         ToolBatchExecutionPlanner toolBatchExecutionPlanner,
                                         ToolBatchExecutor toolBatchExecutor) {
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
        this.completedToolCallContextCompactor = completedToolCallContextCompactor;
        this.generationStageAdmissionService = generationStageAdmissionService;
        this.generationAgentTurnPolicy = generationAgentTurnPolicy;
        this.conversationInitializer = conversationInitializer;
        this.generationAgentCompletionPolicy = Objects.requireNonNull(
                generationAgentCompletionPolicy, "智能体完成判定策略不能为空");
        this.toolBatchExecutionPlanner = Objects.requireNonNull(
                toolBatchExecutionPlanner, "工具批次分段规划器不能为空");
        this.toolBatchExecutor = Objects.requireNonNull(
                toolBatchExecutor, "工具批次执行器不能为空");
    }

    DefaultGenerationAgentRuntime(ChatMemoryStore chatMemoryStore,
                                  ToolManager toolManager,
                                  StreamingModelFactory streamingModelFactory,
                                  ToolExecutionFailurePolicy toolExecutionFailurePolicy,
                                  ToolApprovalService toolApprovalService,
                                  GenerationToolExecutionContextService toolExecutionContextService,
                                  AiToolInvocationPolicy aiToolInvocationPolicy,
                                  GenerationStreamingModelCallSupervisor modelCallSupervisor,
                                  GenerationStageAdmissionService generationStageAdmissionService) {
        this(chatMemoryStore, toolManager, streamingModelFactory, toolExecutionFailurePolicy,
                toolApprovalService, toolExecutionContextService,
                new GenerationPerformanceMonitorService(List.of()), aiToolInvocationPolicy,
                 new DurableToolConversationCodec(),
                 modelCallSupervisor,
                 new CompletedToolCallContextCompactor(
                         new ObjectMapper(), new ChatMemoryProperties()),
                 generationStageAdmissionService,
                 new GenerationAgentTurnPolicy(),
                 null,
                 new GenerationAgentCompletionPolicy(),
                 new ToolBatchExecutionPlanner(toolManager),
                 new ToolBatchExecutor());
    }

    @Override
    public Flux<GenerationStreamEvent> start(GenerationAgentExecutionRequest request) {
        Objects.requireNonNull(request, "智能体执行请求不能为空");
        if (conversationInitializer == null) {
            throw new IllegalStateException("智能体短期会话初始化器不可用");
        }
        return Flux.create(sink -> startInitial(request, sink));
    }

    /**
 * 返回{@code continue}执行后决策。
 *
 * @param approval 审批
 * @param state 状态
 * @param executionContext 执行上下文
 * @param cancelChecker {@code cancelChecker} 对应的调用参数
 * @param cancellationHandleConsumer 对应阶段使用的回调函数
 * @return 异步响应式处理结果
 */
    @Override
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

    private void startInitial(GenerationAgentExecutionRequest request,
                              FluxSink<GenerationStreamEvent> sink) {
        if (request.cancelChecker().getAsBoolean()) {
            sink.complete();
            return;
        }
        GenerationExecutionContext executionContext = request.executionContext();
        AgentLoopState loopState = AgentLoopState.initial(request);
        generationAgentTurnPolicy.beginAttempt(
                executionContext,
                request.codeGenType(),
                request.performanceProfile()
        );
        InvocationContext invocationContext = invocationContext(
                loopState,
                List.of(UserMessage.from(request.userPrompt())),
                executionContext.executionFence()
        );
        ChatMemory chatMemory = conversationInitializer.initialize(
                request, invocationContext);
        ContinuationMemory memory = new ContinuationMemory(
                chatMemory, request.appId(), false);
        aiToolInvocationPolicy.restoreLoopState(
                executionContext.taskId(),
                memory.messages(),
                executionContext.successfulWorkspaceMutationCount()
        );
        try {
            ToolService toolService = createToolService(loopState, executionContext);
            StreamingChatModel model = createExecutionModel(
                    loopState, executionContext);
            AtomicReference<GenerationCancellationHandle> activeCall = new AtomicReference<>();
            Consumer<GenerationCancellationHandle> registerCancellation = handle -> {
                activeCall.set(handle);
                request.cancellationHandleConsumer().accept(handle);
            };
            sink.onCancel(() -> cancel(activeCall.get()));
            requestNextModelTurn(
                    model, memory, toolService, invocationContext,
                    loopState, executionContext, request.cancelChecker(),
                    registerCancellation, sink);
        } catch (RuntimeException failure) {
            sink.error(failure);
        }
    }

    /** 启动 AI 工具{@code Continuation}。 */
    private void start(ToolApprovalRecord approval,
                       GenerationToolContinuationState state,
                       GenerationExecutionContext executionContext,
                       BooleanSupplier cancelChecker,
                       Consumer<GenerationCancellationHandle> cancellationHandleConsumer,
                       FluxSink<GenerationStreamEvent> sink) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (cancelChecker.getAsBoolean()) {
            sink.complete();
            return;
        }
        ContinuationMemory memory = restoreMemory(state, approval.invocationCheckpoint());
        AgentLoopState loopState = AgentLoopState.continuation(state);
        generationAgentTurnPolicy.restoreAttempt(
                executionContext,
                state.codeGenType(),
                state.performanceProfile(),
                memory.messages()
        );
        aiToolInvocationPolicy.restoreLoopState(
                state.taskId(), memory.messages(),
                executionContext.successfulWorkspaceMutationCount());
        ToolService toolService = createToolService(loopState, executionContext);
        InvocationContext invocationContext = invocationContext(
                loopState, memory.messages(), executionContext.executionFence());
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            resolveInterruptedToolRound(
                    approval, loopState, memory, toolService,
                    invocationContext, cancelChecker, sink);
            StreamingChatModel model = createExecutionModel(loopState, executionContext);
            AtomicReference<GenerationCancellationHandle> activeCall = new AtomicReference<>();
            Consumer<GenerationCancellationHandle> registerCancellation = handle -> {
                activeCall.set(handle);
                cancellationHandleConsumer.accept(handle);
            };
            sink.onCancel(() -> cancel(activeCall.get()));
            requestNextModelTurn(
                    model, memory, toolService, invocationContext,
                    loopState, executionContext, cancelChecker, registerCancellation,
                    sink);
        } catch (RuntimeException failure) {
            sink.error(failure);
        }
    }

    private ToolService createToolService(AgentLoopState state,
                                          GenerationExecutionContext executionContext) {
        ToolService toolService = new ToolService();
        toolService.tools(List.of((Object[]) toolManager.getToolsForCodeGen(state.codeGenType())));
        toolService.beforeToolExecution(event -> {
            generationAgentTurnPolicy.assertToolExecutionAllowed(executionContext);
            aiToolInvocationPolicy.authorize(
                    event, state.codeGenType(), state.performanceProfile());
        });
        toolService.afterToolExecution(aiToolInvocationPolicy::complete);
        toolService.maxToolCallingRoundTrips(
                generationAgentTurnPolicy.maximumModelResponses(
                        state.codeGenType(), state.performanceProfile()));
        toolService.executionErrorHandler((failure, context) -> toolExecutionFailurePolicy.handle(
                failure, context, state.codeGenType(), state.performanceProfile()));
        return toolService;
    }

    private StreamingChatModel createExecutionModel(
            AgentLoopState state,
            GenerationExecutionContext executionContext) {
        return streamingModelFactory.createExecutionModel(
                state.performanceProfile(),
                executionContext.limits().modelCallTimeout(),
                null,
                () -> executionContext.consume(
                        GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT));
    }

    /** 根据当前上下文解析{@code Interrupted}工具{@code Round}。 */
    private void resolveInterruptedToolRound(ToolApprovalRecord approval,
                                             AgentLoopState state,
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
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
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
                result = executeToolWithSpan(state, invocationContext, request,
                        () -> decisionResult(approval, toolService, invocationContext, request));
            } else {
                result = executeToolWithSpan(state, invocationContext, request,
                        () -> toolService.executeTool(
                                invocationContext, toolService.toolExecutors(),
                                request, null, null));
            }
            memory.add(toResultMessage(request, result));
            sink.next(toolResultEvent(request, result));
        }
        if (!checkpointResolved) {
            throw new IllegalStateException("短期记忆中缺少待处理的工具调用");
        }
    }

    /** 返回决策结果。 */
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
                    .resultText("项目所有者拒绝了该破坏性工具操作，请选择安全的替代方案。")
                    .build();
        }
        if (approval.status() == ToolApprovalStatus.EXPIRED) {
            return ToolExecutionResult.builder()
                    .isError(true)
                    .resultText("破坏性工具操作的审批已过期，请选择安全的替代方案。")
                    .build();
        }
        throw new IllegalStateException("当前工具审批状态无法恢复执行：" + approval.status());
    }

    /** 执行已审批调用处理流程。 */
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
            throw new IllegalStateException("已完成的工具调用缺少持久化结果");
        }
        return ToolExecutionResult.builder()
                .isError(outcome.error())
                .resultText(outcome.resultText())
                .build();
    }

    /** 处理请求{@code Next}模型轮次。 */
    private void requestNextModelTurn(StreamingChatModel model,
                                      ContinuationMemory memory,
                                      ToolService toolService,
                                      InvocationContext invocationContext,
                                      AgentLoopState state,
                                      GenerationExecutionContext executionContext,
                                      BooleanSupplier cancelChecker,
                                      Consumer<GenerationCancellationHandle> cancellationHandleConsumer,
                                      FluxSink<GenerationStreamEvent> sink) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (cancelChecker.getAsBoolean() || sink.isCancelled()) {
            sink.complete();
            return;
        }
        GenerationStageAdmissionService.ModelTurnWindow modelTurnWindow;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            modelTurnWindow = generationStageAdmissionService.requireModelTurn(
                    executionContext,
                    state.codeGenType(),
                    state.route()
            );
        } catch (GenerationModelTurnAdmissionException admission) {
            completeForReservedWindow(state, executionContext, admission, sink);
            return;
        }
        ChatRequest request = completedToolCallContextCompactor.compact(ChatRequest.builder()
                .messages(memory.messages())
                .toolSpecifications(toolService.toolSpecifications())
                .build());
        request = aiToolInvocationPolicy.governModelTurn(
                state.taskId(), executionContext.successfulWorkspaceMutationCount(), request);
        GenerationAgentTurnPolicy.PreparedModelTurn preparedTurn =
                generationAgentTurnPolicy.prepareModelTurn(
                        executionContext,
                        state.codeGenType(),
                        state.performanceProfile(),
                        request
                );
        request = preparedTurn.request();
        ReasoningProgressTracker reasoningProgress = new ReasoningProgressTracker(state.taskId());
        modelCallSupervisor.chat(
                model,
                request,
                executionContext,
                modelTurnWindow.timeout(),
                cancelChecker,
                cancellationHandleConsumer,
                new StreamingChatResponseHandler() {
                    private final AtomicBoolean terminated = new AtomicBoolean();

            /**
 * 响应部分响应事件。
 *
 * @param partialResponse 部分响应
 * @param context 执行上下文
 */
            @Override
            public void onPartialResponse(PartialResponse partialResponse,
                                          PartialResponseContext context) {
                if (!cancelChecker.getAsBoolean() && !sink.isCancelled()) {
                    reasoningProgress.completeIfStarted().ifPresent(sink::next);
                    sink.next(GenerationStreamEvent.aiDelta(partialResponse.text()));
                }
            }

            /**
 * 响应部分{@code Thinking}事件。
 *
 * @param partialThinking {@code partialThinking} 对应的调用参数
 * @param context 执行上下文
 */
            @Override
            public void onPartialThinking(PartialThinking partialThinking,
                                          PartialThinkingContext context) {
                if (!cancelChecker.getAsBoolean() && !sink.isCancelled()) {
                    reasoningProgress.startIfNeeded().ifPresent(sink::next);
                }
            }

            /**
 * 响应{@code Complete}响应事件。
 *
 * @param response 响应对象
 */
            @Override
            public void onCompleteResponse(ChatResponse response) {
                // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
                if (!terminated.compareAndSet(false, true) || sink.isCancelled()) {
                    return;
                }
                // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
                try {
                    reasoningProgress.completeIfStarted().ifPresent(sink::next);
                    AiMessage aiMessage = response.aiMessage();
                    memory.add(aiMessage);
                    if (!aiMessage.hasToolExecutionRequests()) {
                        handleCompletionAttempt(
                                model, memory, toolService, invocationContext, state,
                                executionContext, preparedTurn, cancelChecker,
                                cancellationHandleConsumer, sink);
                        return;
                    }
                    if (!preparedTurn.toolCallsAllowed()) {
                        sink.error(new IllegalStateException(
                                "模型在最终收口回合违反了工具禁用约束"));
                        return;
                    }
                    boolean anyToolErrored = false;
                    List<ReturnBehavior> returnBehaviors = new ArrayList<>();
                    List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
                    // 工具调用事件先按原序发出，用户看到的调用顺序与模型请求顺序一致。
                    for (int index = 0; index < toolRequests.size(); index++) {
                        sink.next(toolCallEvent(toolRequests.get(index), index));
                    }
                    // 只读工具并发执行，写/破坏性工具保持串行；结果按原序回填。
                    List<ToolExecutionResult> results = toolBatchExecutor.execute(
                            toolBatchExecutionPlanner.plan(toolRequests),
                            toolRequests.size(),
                            indexed -> executeToolWithSpan(
                                    state,
                                    invocationContext,
                                    indexed.request(),
                                    () -> toolService.executeTool(
                                            invocationContext, toolService.toolExecutors(),
                                            indexed.request(), null, null)
                            )
                    );
                    for (int index = 0; index < toolRequests.size(); index++) {
                        ToolExecutionRequest toolRequest = toolRequests.get(index);
                        ToolExecutionResult result = results.get(index);
                        memory.add(toResultMessage(toolRequest, result));
                        sink.next(toolResultEvent(toolRequest, result));
                        anyToolErrored = anyToolErrored || result.isError();
                        returnBehaviors.add(toolService.returnBehavior(toolRequest.name()));
                    }
                    if (ToolService.shouldReturnImmediately(anyToolErrored, returnBehaviors)) {
                        handleCompletionAttempt(
                                model, memory, toolService, invocationContext, state,
                                executionContext, preparedTurn, cancelChecker,
                                cancellationHandleConsumer, sink);
                        return;
                    }
                    requestNextModelTurn(
                            model, memory, toolService, invocationContext, state,
                            executionContext, cancelChecker, cancellationHandleConsumer,
                            sink);
                } catch (RuntimeException failure) {
                    sink.error(failure);
                }
            }

            /**
 * 响应错误事件。
 *
 * @param error 错误
 */
            @Override
            public void onError(Throwable error) {
                if (terminated.compareAndSet(false, true) && !sink.isCancelled()) {
                    if (error instanceof GenerationModelCallTimeoutException
                            && modelTurnWindow.completionWindowLimited()
                            && !executionContext.isDeadlineExceeded()) {
                        reasoningProgress.completeIfStarted().ifPresent(sink::next);
                        GenerationModelTurnAdmissionException admission =
                                generationStageAdmissionService.completionWindowReached(
                                        executionContext,
                                        state.route(),
                                        modelTurnWindow
                                );
                        completeForReservedWindow(state, executionContext, admission, sink);
                        return;
                    }
                    reasoningProgress.failIfStarted().ifPresent(sink::next);
                    sink.error(error);
                }
            }

                });
    }

    /** 根据工作区变更证据判定结束、纠偏重试或失败关闭。 */
    private void handleCompletionAttempt(StreamingChatModel model,
                                         ContinuationMemory memory,
                                         ToolService toolService,
                                         InvocationContext invocationContext,
                                         AgentLoopState state,
                                         GenerationExecutionContext executionContext,
                                         GenerationAgentTurnPolicy.PreparedModelTurn preparedTurn,
                                         BooleanSupplier cancelChecker,
                                         Consumer<GenerationCancellationHandle> cancellationHandleConsumer,
                                         FluxSink<GenerationStreamEvent> sink) {
        GenerationAgentCompletionPolicy.AgentCompletionDecision decision =
                generationAgentCompletionPolicy.evaluate(executionContext, preparedTurn);
        if (decision.completable()) {
            completeGeneration(state, sink);
            return;
        }
        if (!decision.retryable()) {
            sink.error(decision.toException());
            return;
        }
        memory.add(UserMessage.from(decision.message()));
        requestNextModelTurn(
                model, memory, toolService, invocationContext, state,
                executionContext, cancelChecker, cancellationHandleConsumer, sink);
    }

    private void completeGeneration(AgentLoopState state,
                                    FluxSink<GenerationStreamEvent> sink) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "transition");
        data.put("stage", "codegen_done");
        data.put("summary", state.continuation()
                ? "工具审批结果已处理，代码生成阶段已完成"
                : state.codeGenType()
                == com.rush.rushaicodemother.model.enums.CodeGenTypeEnum.VUE_PROJECT
                ? "代码已生成，后台正在执行构建校验"
                : "代码已生成");
        data.put("taskId", state.taskId());
        if (state.projectPath() != null && !state.projectPath().isBlank()) {
            data.put("projectPath", state.projectPath());
        }
        sink.next(GenerationStreamEvent.generationStage(
                "代码生成完成", Map.copyOf(data)));
        sink.complete();
    }

    /** 完成{@code For}{@code Reserved}窗口并持久化终态。 */
    private void completeForReservedWindow(AgentLoopState state,
                                           GenerationExecutionContext executionContext,
                                           GenerationModelTurnAdmissionException admission,
                                           FluxSink<GenerationStreamEvent> sink) {
        if (executionContext.successfulWorkspaceMutationCount() <= 0) {
            sink.error(new GenerationAgentCompletionPolicy.AgentCompletionDecision(
                    false, false,
                    "模型调用已进入预留收口窗口，但尚未产生有效工作区变更，不能标记代码生成完成")
                    .toException());
            return;
        }
        sink.next(GenerationStreamEvent.agentEvent("", Map.of(
                "agent", "DeadlinePolicy",
                "stage", "model_turn_admission",
                "status", "reserved_completion",
                "reason", "completion_window_reserved",
                "remainingMs", admission.remaining().toMillis(),
                "requiredMs", admission.required().toMillis(),
                "completionReserveMs", admission.completionReserve().toMillis(),
                "taskId", state.taskId()
        )));
        sink.next(GenerationStreamEvent.generationStage(
                "模型阶段已收口，正在执行工程校验",
                Map.of(
                        "status", "transition",
                        "stage", "codegen_done",
                        "reason", "completion_window_reserved",
                        "summary", "已保留构建、验证与发布所需时间",
                        "taskId", state.taskId()
                )
        ));
        sink.complete();
    }

    /** 返回调用上下文。 */
    private InvocationContext invocationContext(AgentLoopState state,
                                                List<ChatMessage> messages,
                                                com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence executionFence) {
        UserMessage userMessage = messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .reduce((left, right) -> right)
                .orElseGet(() -> UserMessage.from(state.userPrompt()));
        return InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .interfaceName(state.interfaceName())
                .methodName(state.methodName())
                .methodArguments(List.of(state.taskId()))
                .userMessage(userMessage)
                .chatMemoryId(state.appId())
                .invocationParameters(InvocationParameters.from(
                        GenerationToolExecutionContextService.EXECUTION_FENCE_PARAMETER,
                        executionFence))
                .timestampNow()
                .build();
    }

    /** 返回恢复记忆。 */
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

    /** 查找匹配的{@code Interrupted}{@code Assistant}消息。 */
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
        throw new IllegalStateException("待处理工具调用与短期记忆不一致");
    }

    /** 完成{@code d}请求{@code Ids}并持久化终态。 */
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

    /** 执行工具并跨度处理流程。 */
    private ToolExecutionResult executeToolWithSpan(AgentLoopState state,
                                                    InvocationContext invocationContext,
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
        } catch (GenerationApprovalRequiredException approvalRequired) {
            timer.failed("等待人工审批");
            toolExecutionFailurePolicy.prepareApprovalSuspension(
                    approvalRequired,
                    request,
                    state.codeGenType(),
                    state.performanceProfile(),
                    invocationContext == null ? null : invocationContext.userMessage()
            );
            throw approvalRequired;
        } catch (RuntimeException failure) {
            timer.failed(failure.getMessage());
            throw failure;
        }
    }

    /** 校验并返回有效的{@code Continuation}。 */
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
            throw new IllegalArgumentException("工具恢复决策与检查点不一致");
        }
    }

    private record AgentLoopState(
            String taskId,
            Long appId,
            String userPrompt,
            com.rush.rushaicodemother.model.enums.CodeGenTypeEnum codeGenType,
            com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile performanceProfile,
            String route,
            String projectPath,
            String interfaceName,
            String methodName,
        boolean continuation
    ) {
        private static AgentLoopState initial(GenerationAgentExecutionRequest request) {
            return new AgentLoopState(
                    request.executionContext().taskId(),
                    request.appId(),
                    request.userPrompt(),
                    request.codeGenType(),
                    request.performanceProfile(),
                    "code_generation",
                    request.projectPath(),
                    GenerationAgentRuntime.class.getName(),
                    "start",
                    false
            );
        }

        private static AgentLoopState continuation(
                GenerationToolContinuationState state) {
            return new AgentLoopState(
                    state.taskId(),
                    state.appId(),
                    state.userPrompt(),
                    state.codeGenType(),
                    state.performanceProfile(),
                    state.route(),
                    null,
                    GenerationAgentRuntime.class.getName(),
                    "continueAfterDecision",
                    true
            );
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
