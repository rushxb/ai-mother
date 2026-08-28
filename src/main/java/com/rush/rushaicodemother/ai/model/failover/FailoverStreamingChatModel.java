package com.rush.rushaicodemother.ai.model.failover;

import com.rush.rushaicodemother.core.handler.GenerationCancellationAwareStreamingHandler;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCancellationScope;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * 流式故障转移，仅在发出任何用户可见的输出之前重试另一个模型。
 */
public final class FailoverStreamingChatModel implements StreamingChatModel {

    private final List<AiModelCandidate<StreamingChatModel>> candidates;
    private final AiModelMetricsCollector metrics;
    private final IntConsumer beforeProviderAttempt;
    private final FirstTokenHedgePolicy firstTokenHedgePolicy;
    private final GenerationModelInvocationCancellationBridge cancellationBridge;

    public FailoverStreamingChatModel(List<AiModelCandidate<StreamingChatModel>> candidates,
                                      AiModelMetricsCollector metrics) {
        this(candidates, metrics, ignored -> { }, FirstTokenHedgePolicy.disabled(), null);
    }

    public FailoverStreamingChatModel(List<AiModelCandidate<StreamingChatModel>> candidates,
                                      AiModelMetricsCollector metrics,
                                      GenerationModelInvocationCancellationBridge cancellationBridge) {
        this(candidates, metrics, ignored -> { },
                FirstTokenHedgePolicy.disabled(), cancellationBridge);
    }

    public FailoverStreamingChatModel(List<AiModelCandidate<StreamingChatModel>> candidates,
                                      AiModelMetricsCollector metrics,
                                      Runnable beforeProviderAttempt) {
        this(candidates, metrics, beforeProviderAttempt, FirstTokenHedgePolicy.disabled(), null);
    }

    public FailoverStreamingChatModel(List<AiModelCandidate<StreamingChatModel>> candidates,
                                      AiModelMetricsCollector metrics,
                                      Runnable beforeProviderAttempt,
                                      FirstTokenHedgePolicy firstTokenHedgePolicy) {
        this(candidates, metrics, beforeProviderAttempt, firstTokenHedgePolicy, null);
    }

    /**
 * 创建故障转移{@code Streaming}对话模型实例并完成必要的依赖和初始状态设置。
 *
 * @param candidates 待处理的 {@code candidates} 集合
 * @param metrics 待处理的 {@code metrics} 集合
 * @param beforeProviderAttempt 执行前提供方尝试
 * @param firstTokenHedgePolicy {@code firstTokenHedgePolicy} 对应的调用参数
 * @param cancellationBridge {@code cancellationBridge} 对应的调用参数
 */
    public FailoverStreamingChatModel(List<AiModelCandidate<StreamingChatModel>> candidates,
                                      AiModelMetricsCollector metrics,
                                      Runnable beforeProviderAttempt,
                                      FirstTokenHedgePolicy firstTokenHedgePolicy,
                                      GenerationModelInvocationCancellationBridge cancellationBridge) {
        this(candidates, metrics, ignored -> {
            if (beforeProviderAttempt != null) {
                beforeProviderAttempt.run();
            }
        }, firstTokenHedgePolicy, cancellationBridge);
    }

    /** 将一次逻辑模型回合与其后续 provider 故障转移分别纳入预算。 */
    public FailoverStreamingChatModel(List<AiModelCandidate<StreamingChatModel>> candidates,
                                      AiModelMetricsCollector metrics,
                                      Runnable beforeModelTurn,
                                      Runnable beforeProviderFailoverAttempt) {
        this(candidates, metrics, beforeModelTurn, beforeProviderFailoverAttempt,
                FirstTokenHedgePolicy.disabled(), null);
    }

    public FailoverStreamingChatModel(List<AiModelCandidate<StreamingChatModel>> candidates,
                                      AiModelMetricsCollector metrics,
                                      Runnable beforeModelTurn,
                                      Runnable beforeProviderFailoverAttempt,
                                      FirstTokenHedgePolicy firstTokenHedgePolicy) {
        this(candidates, metrics, beforeModelTurn, beforeProviderFailoverAttempt,
                firstTokenHedgePolicy, null);
    }

    /**
 * 创建故障转移{@code Streaming}对话模型实例并完成必要的依赖和初始状态设置。
 *
 * @param candidates 待处理的 {@code candidates} 集合
 * @param metrics 待处理的 {@code metrics} 集合
 * @param beforeModelTurn 每轮模型调用前执行的回调
 * @param beforeProviderFailoverAttempt 模型提供方故障转移前执行的回调
 * @param firstTokenHedgePolicy {@code firstTokenHedgePolicy} 对应的调用参数
 * @param cancellationBridge {@code cancellationBridge} 对应的调用参数
 */
    public FailoverStreamingChatModel(List<AiModelCandidate<StreamingChatModel>> candidates,
                                      AiModelMetricsCollector metrics,
                                      Runnable beforeModelTurn,
                                      Runnable beforeProviderFailoverAttempt,
                                      FirstTokenHedgePolicy firstTokenHedgePolicy,
                                      GenerationModelInvocationCancellationBridge cancellationBridge) {
        this(candidates, metrics, attemptIndex -> {
            Runnable admission = attemptIndex == 0
                    ? beforeModelTurn
                    : beforeProviderFailoverAttempt;
            if (admission != null) {
                admission.run();
            }
        }, firstTokenHedgePolicy, cancellationBridge);
    }

    /** 创建故障转移{@code Streaming}对话模型实例并完成必要的依赖和初始状态设置。 */
    private FailoverStreamingChatModel(List<AiModelCandidate<StreamingChatModel>> candidates,
                                       AiModelMetricsCollector metrics,
                                       IntConsumer beforeProviderAttempt,
                                       FirstTokenHedgePolicy firstTokenHedgePolicy,
                                       GenerationModelInvocationCancellationBridge cancellationBridge) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("at least one streaming model candidate is required");
        }
        this.candidates = List.copyOf(candidates);
        this.metrics = metrics;
        this.beforeProviderAttempt = beforeProviderAttempt == null ? ignored -> { } : beforeProviderAttempt;
        this.firstTokenHedgePolicy = firstTokenHedgePolicy == null
                ? FirstTokenHedgePolicy.disabled()
                : firstTokenHedgePolicy;
        this.cancellationBridge = cancellationBridge;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        execute(request, null, handler);
    }

    @Override
    public void chat(ChatRequest request,
                     ChatRequestOptions options,
                     StreamingChatResponseHandler handler) {
        execute(request, options, handler);
    }

    /**
 * 返回默认请求{@code Parameters}。
 *
 * @return 故障转移{@code Streaming}对话模型
 */
    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return candidates.getFirst().model().defaultRequestParameters();
    }

    /**
 * 返回提供方。
 *
 * @return 故障转移{@code Streaming}对话模型
 */
    @Override
    public ModelProvider provider() {
        return candidates.getFirst().model().provider();
    }

    /**
 * 返回支持的能力。
 *
 * @return 故障转移{@code Streaming}对话模型集合
 */
    @Override
    public Set<Capability> supportedCapabilities() {
        Set<Capability> intersection = new LinkedHashSet<>(
                candidates.getFirst().model().supportedCapabilities());
        for (int index = 1; index < candidates.size(); index++) {
            intersection.retainAll(candidates.get(index).model().supportedCapabilities());
        }
        return Set.copyOf(intersection);
    }

    /** 执行故障转移{@code Streaming}对话模型处理流程。 */
    private void execute(ChatRequest request,
                         ChatRequestOptions options,
                         StreamingChatResponseHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("streaming response handler is required");
        }
        GenerationModelInvocationCancellationBridge.ScopeBinding scopeBinding =
                cancellationBridge == null ? null : cancellationBridge.bind(request);
        try {
            executeBound(request, options, handler,
                    scopeBinding == null ? null : scopeBinding.scope());
        } finally {
            if (scopeBinding != null) {
                scopeBinding.close();
            }
        }
    }

    /** 执行绑定处理流程。 */
    private void executeBound(ChatRequest request,
                              ChatRequestOptions options,
                              StreamingChatResponseHandler handler,
                              GenerationModelCancellationScope cancellationScope) {
        List<Integer> candidateOrder = candidateOrder();
        if (firstTokenHedgePolicy.canHedge(candidates, candidateOrder)) {
            HedgedStreamingExecution execution = new HedgedStreamingExecution(
                    candidates,
                    candidateOrder,
                    metrics,
                    beforeProviderAttempt,
                    firstTokenHedgePolicy,
                    request,
                    options,
                    handler,
                    cancellationBridge,
                    cancellationScope
            );
            if (cancellationScope != null) {
                cancellationScope.register(execution);
            }
            execution.start();
            return;
        }
        AttemptState state = new AttemptState(handler, candidateOrder, cancellationScope);
        executeAttempt(0, request, options, state);
    }

    /** 执行尝试处理流程。 */
    private void executeAttempt(int attemptIndex,
                                ChatRequest request,
                                ChatRequestOptions options,
                                AttemptState state) {
        int candidateIndex = state.candidateOrder.get(attemptIndex);
        AiModelCandidate<StreamingChatModel> candidate = candidates.get(candidateIndex);
        AttemptGuard attempt = new AttemptGuard();
        StreamingChatResponseHandler forwarding = forwardingHandler(
                attemptIndex, candidateIndex, request, options, state, candidate, attempt);
        try {
            if (state.cancellationScope != null) {
                state.cancellationScope.register(attempt);
            }
            GenerationCancellationAwareStreamingHandler.registerIfSupported(state.downstream, attempt);
            if (attempt.isCancelled()) {
                return;
            }
            beforeProviderAttempt.accept(attemptIndex);
            invokeCandidate(candidate, request, options, forwarding, state.cancellationScope);
        } catch (RuntimeException failure) {
            AttemptFailure attemptFailure = attempt.finishFailure();
            if (attemptFailure != null) {
                handleFailure(attemptIndex, request, options, state, candidate,
                        attemptFailure.outputObserved(), failure);
            }
        }
    }

    private void invokeCandidate(AiModelCandidate<StreamingChatModel> candidate,
                                 ChatRequest request,
                                 ChatRequestOptions options,
                                 StreamingChatResponseHandler forwarding,
                                 GenerationModelCancellationScope cancellationScope) {
        if (cancellationBridge == null) {
            invokeCandidate(candidate, request, options, forwarding);
            return;
        }
        try (GenerationModelInvocationCancellationBridge.ScopeBinding ignored =
                     cancellationBridge.activate(cancellationScope)) {
            invokeCandidate(candidate, request, options, forwarding);
        }
    }

    private void invokeCandidate(AiModelCandidate<StreamingChatModel> candidate,
                                 ChatRequest request,
                                 ChatRequestOptions options,
                                 StreamingChatResponseHandler forwarding) {
        if (options == null) {
            candidate.model().chat(request, forwarding);
        } else {
            candidate.model().chat(request, options, forwarding);
        }
    }

    /** 转发{@code ing}处理器。 */
    private StreamingChatResponseHandler forwardingHandler(
            int attemptIndex,
            int candidateIndex,
            ChatRequest request,
            ChatRequestOptions options,
            AttemptState state,
            AiModelCandidate<StreamingChatModel> candidate,
            AttemptGuard attempt) {
        return new GenerationCancellationAwareStreamingHandler() {
            @Override
            public void registerCancellationHandle(GenerationCancellationHandle cancellationHandle) {
                attempt.registerCancellationHandle(cancellationHandle);
            }

            /**
 * 响应部分响应事件。
 *
 * @param partialResponse 部分响应
 */
            @Override
            public void onPartialResponse(String partialResponse) {
                attempt.forward(() -> state.downstream.onPartialResponse(partialResponse));
            }

            /**
 * 响应部分响应事件。
 *
 * @param partialResponse 部分响应
 * @param context 执行上下文
 */
            @Override
            public void onPartialResponse(PartialResponse partialResponse,
                                          PartialResponseContext context) {
                attempt.forward(() -> state.downstream.onPartialResponse(partialResponse, context));
            }

            /**
 * 响应部分{@code Thinking}事件。
 *
 * @param partialThinking {@code partialThinking} 对应的调用参数
 */
            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                attempt.forward(() -> state.downstream.onPartialThinking(partialThinking));
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
                attempt.forward(() -> state.downstream.onPartialThinking(partialThinking, context));
            }

            /**
 * 响应部分工具调用事件。
 *
 * @param partialToolCall 部分工具调用
 */
            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall) {
                attempt.forward(() -> state.downstream.onPartialToolCall(partialToolCall));
            }

            /**
 * 响应部分工具调用事件。
 *
 * @param partialToolCall 部分工具调用
 * @param context 执行上下文
 */
            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall,
                                          PartialToolCallContext context) {
                attempt.forward(() -> state.downstream.onPartialToolCall(partialToolCall, context));
            }

            /**
 * 响应{@code Complete}工具调用事件。
 *
 * @param completeToolCall {@code completeToolCall} 对应的调用参数
 */
            @Override
            public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                attempt.forward(() -> state.downstream.onCompleteToolCall(completeToolCall));
            }

            /**
 * 响应{@code Unmapped}原始事件事件。
 *
 * @param event 待处理的领域事件
 */
            @Override
            public void onUnmappedRawEvent(Object event) {
                attempt.forward(() -> state.downstream.onUnmappedRawEvent(event));
            }

            /**
 * 响应{@code Complete}响应事件。
 *
 * @param completeResponse {@code completeResponse} 对应的调用参数
 */
            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                if (attempt.finish()
                        && state.terminal.compareAndSet(false, true)) {
                    state.downstream.onCompleteResponse(completeResponse);
                }
            }

            /**
 * 响应错误事件。
 *
 * @param error 错误
 */
            @Override
            public void onError(Throwable error) {
                AttemptFailure attemptFailure = attempt.finishFailure();
                if (attemptFailure != null) {
                    handleFailure(attemptIndex, request, options, state, candidate,
                            attemptFailure.outputObserved(), error);
                }
            }
        };
    }

    /** 处理失败。 */
    private void handleFailure(int attemptIndex,
                               ChatRequest request,
                               ChatRequestOptions options,
                               AttemptState state,
                               AiModelCandidate<StreamingChatModel> candidate,
                               boolean outputObserved,
                               Throwable failure) {
        if (state.terminal.get()) {
            return;
        }
        state.failures.add(failure);
        AiModelFailoverPolicy.Decision decision = AiModelFailoverPolicy.classify(failure);
        boolean canFailOver = !outputObserved
                && decision.recoverable()
                && attemptIndex + 1 < state.candidateOrder.size();
        if (canFailOver) {
            AiModelCandidate<StreamingChatModel> next = candidates.get(
                    state.candidateOrder.get(attemptIndex + 1));
            recordFailover(candidate, next, decision.category());
            executeAttempt(attemptIndex + 1, request, options, state);
            return;
        }
        if (state.terminal.compareAndSet(false, true)) {
            state.downstream.onError(withSuppressed(failure, state.failures));
        }
    }

    /** 创建包含{@code Suppressed}的新对象。 */
    private Throwable withSuppressed(Throwable terminal, List<Throwable> failures) {
        for (Throwable failure : failures) {
            if (failure != terminal) {
                terminal.addSuppressed(failure);
            }
        }
        return terminal;
    }

    private void recordFailover(AiModelCandidate<?> from,
                                AiModelCandidate<?> to,
                                String errorCategory) {
        if (metrics != null) {
            metrics.recordFailover(
                    from.provider(), from.modelId(), to.provider(), to.modelId(),
                    errorCategory);
        }
    }

    private List<Integer> candidateOrder() {
        List<Integer> order = new java.util.ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            order.add(index);
        }
        return List.copyOf(order);
    }

    private static final class AttemptState {
        private final StreamingChatResponseHandler downstream;
        private final List<Integer> candidateOrder;
        private final GenerationModelCancellationScope cancellationScope;
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private final List<Throwable> failures = new CopyOnWriteArrayList<>();

        private AttemptState(StreamingChatResponseHandler downstream,
                             List<Integer> candidateOrder,
                             GenerationModelCancellationScope cancellationScope) {
            this.downstream = downstream;
            this.candidateOrder = candidateOrder;
            this.cancellationScope = cancellationScope;
        }
    }

    private static final class AttemptGuard implements GenerationCancellationHandle {
        private boolean finished;
        private boolean cancelled;
        private boolean outputObserved;
        private GenerationCancellationHandle cancellationHandle;

        private synchronized void forward(Runnable callback) {
            if (finished || cancelled) {
                return;
            }
            outputObserved = true;
            callback.run();
        }

        private synchronized boolean finish() {
            if (finished || cancelled) {
                return false;
            }
            finished = true;
            cancellationHandle = null;
            return true;
        }

        private synchronized boolean isCancelled() {
            return cancelled;
        }

        private synchronized AttemptFailure finishFailure() {
            if (finished || cancelled) {
                return null;
            }
            finished = true;
            cancellationHandle = null;
            return new AttemptFailure(outputObserved);
        }

        /** 注册{@code Cancellation}句柄。 */
        private void registerCancellationHandle(GenerationCancellationHandle handle) {
            GenerationCancellationHandle previous;
            boolean cancelImmediately;
            synchronized (this) {
                if (handle == null) {
                    throw new IllegalArgumentException("供应商取消句柄不能为空");
                }
                if (finished || cancelled) {
                    previous = handle;
                    cancelImmediately = true;
                } else {
                    previous = cancellationHandle;
                    cancellationHandle = handle;
                    cancelImmediately = false;
                }
            }
            if (cancelImmediately || previous != handle) {
                cancelRegistered(previous);
            }
        }

        /** 取消尝试防护。 */
        @Override
        public void cancel() {
            GenerationCancellationHandle active;
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                active = cancellationHandle;
                cancellationHandle = null;
            }
            cancelRegistered(active);
        }

        private void cancelRegistered(GenerationCancellationHandle handle) {
            if (handle != null) {
                handle.cancel();
            }
        }
    }

    private record AttemptFailure(boolean outputObserved) {
    }
}
