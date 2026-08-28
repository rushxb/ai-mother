package com.rush.rushaicodemother.ai.model.failover;

import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 跨有限的、健康的模型池进行同步请求级故障转移。 */
public final class FailoverChatModel implements ChatModel {

    private static final ThreadFactory PROVIDER_CALL_THREAD_FACTORY =
            Thread.ofVirtual().name("ai-chat-failover-", 0).factory();

    private final List<AiModelCandidate<ChatModel>> candidates;
    private final AiModelMetricsCollector metrics;
    private final Duration totalTimeout;
    private final LongSupplier nanoTime;
    private final IntConsumer beforeProviderAttempt;

    public FailoverChatModel(List<AiModelCandidate<ChatModel>> candidates,
                             AiModelMetricsCollector metrics) {
        this(candidates, metrics, null, System::nanoTime, ignored -> { });
    }

    /** 创建一个故障转移池，其中每个候选者共享一个挂钟预算。 */
    public FailoverChatModel(List<AiModelCandidate<ChatModel>> candidates,
                             AiModelMetricsCollector metrics,
                             Duration totalTimeout) {
        this(candidates, metrics, totalTimeout, System::nanoTime, ignored -> { });
    }

    /** 将同步模型回合和 provider 故障转移使用独立预算约束。 */
    public FailoverChatModel(List<AiModelCandidate<ChatModel>> candidates,
                             AiModelMetricsCollector metrics,
                             Duration totalTimeout,
                             Runnable beforeModelTurn,
                             Runnable beforeProviderFailoverAttempt) {
        this(candidates, metrics, totalTimeout, System::nanoTime, attemptIndex -> {
            Runnable admission = attemptIndex == 0
                    ? beforeModelTurn
                    : beforeProviderFailoverAttempt;
            if (admission != null) {
                admission.run();
            }
        });
    }

    FailoverChatModel(List<AiModelCandidate<ChatModel>> candidates,
                      AiModelMetricsCollector metrics,
                      Duration totalTimeout,
                      LongSupplier nanoTime) {
        this(candidates, metrics, totalTimeout, nanoTime, ignored -> { });
    }

    /** 创建故障转移对话模型实例并完成必要的依赖和初始状态设置。 */
    private FailoverChatModel(List<AiModelCandidate<ChatModel>> candidates,
                              AiModelMetricsCollector metrics,
                              Duration totalTimeout,
                              LongSupplier nanoTime,
                              IntConsumer beforeProviderAttempt) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("at least one chat model candidate is required");
        }
        if (totalTimeout != null && (totalTimeout.isZero() || totalTimeout.isNegative())) {
            throw new IllegalArgumentException("chat model failover timeout must be positive");
        }
        this.candidates = List.copyOf(candidates);
        this.metrics = metrics;
        this.totalTimeout = totalTimeout;
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
        this.beforeProviderAttempt = beforeProviderAttempt == null ? ignored -> { } : beforeProviderAttempt;
    }

    /**
 * 返回对话。
 *
 * @param request 请求参数
 * @return 故障转移对话模型
 */
    @Override
    public ChatResponse chat(ChatRequest request) {
        return execute(model -> model.chat(request));
    }

    /**
 * 返回对话。
 *
 * @param request 请求参数
 * @param options 待处理的 {@code options} 集合
 * @return 故障转移对话模型
 */
    @Override
    public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        return execute(model -> model.chat(request, options));
    }

    /**
 * 返回默认请求{@code Parameters}。
 *
 * @return 故障转移对话模型
 */
    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return candidates.getFirst().model().defaultRequestParameters();
    }

    /**
 * 返回提供方。
 *
 * @return 故障转移对话模型
 */
    @Override
    public ModelProvider provider() {
        return candidates.getFirst().model().provider();
    }

    /**
 * 返回支持的能力。
 *
 * @return 故障转移对话模型集合
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

    /** 执行故障转移对话模型处理流程。 */
    private ChatResponse execute(Function<ChatModel, ChatResponse> invocation) {
        List<RuntimeException> failures = new ArrayList<>();
        long deadlineNanos = deadlineNanos();
        List<Integer> candidateOrder = candidateOrder();
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (int attemptIndex = 0; attemptIndex < candidateOrder.size(); attemptIndex++) {
            if (attemptIndex > 0 && deadlineReached(deadlineNanos)) {
                throw timeout(failures);
            }
            int candidateIndex = candidateOrder.get(attemptIndex);
            AiModelCandidate<ChatModel> candidate = candidates.get(candidateIndex);
            ChatResponse response;
            try {
                beforeProviderAttempt.accept(attemptIndex);
                response = invokeWithinDeadline(invocation, candidate.model(), deadlineNanos);
            } catch (InFlightTimeoutException ignored) {
                throw timeout(failures);
            } catch (RuntimeException failure) {
                failures.add(failure);
                AiModelFailoverPolicy.Decision decision = AiModelFailoverPolicy.classify(failure);
                if (!decision.recoverable() || attemptIndex + 1 >= candidateOrder.size()) {
                    throw withSuppressed(failure, failures);
                }
                if (deadlineReached(deadlineNanos)) {
                    throw timeout(failures);
                }
                recordFailover(candidate,
                        candidates.get(candidateOrder.get(attemptIndex + 1)),
                        decision.category());
                continue;
            }
            // Provider 即便恰好在等待边界返回，也不能突破整个候选池的挂钟预算。
            if (deadlineReached(deadlineNanos)) {
                throw timeout(failures);
            }
            return response;
        }
        throw new IllegalStateException("chat model failover pool completed without a result");
    }

    /**
     * 在独立虚拟线程中监督单个 Provider 调用，确保第三方传输忽略中断或自身超时配置时，
     * 调用方仍能在模型池总预算内返回。无总超时的兼容路径继续在当前线程执行。
     */
    private ChatResponse invokeWithinDeadline(Function<ChatModel, ChatResponse> invocation,
                                              ChatModel model,
                                              long deadlineNanos) {
        if (deadlineNanos == Long.MAX_VALUE) {
            return invocation.apply(model);
        }
        long remainingNanos = remainingNanos(deadlineNanos);
        if (remainingNanos <= 0L) {
            throw new InFlightTimeoutException();
        }
        MonitorContext capturedContext = MonitorContextHolder.getContext();
        FutureTask<ChatResponse> providerCall = new FutureTask<>(
                () -> invokeWithMonitorContext(capturedContext, () -> invocation.apply(model)));
        Thread providerThread = PROVIDER_CALL_THREAD_FACTORY.newThread(providerCall);
        providerThread.start();
        try {
            return providerCall.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException waitingForProvider) {
            providerCall.cancel(true);
            throw new InFlightTimeoutException();
        } catch (InterruptedException interrupted) {
            providerCall.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("chat model failover was interrupted", interrupted);
        } catch (ExecutionException providerFailure) {
            throw propagate(providerFailure.getCause());
        } finally {
            if (!providerCall.isDone()) {
                providerCall.cancel(true);
            }
        }
    }

    /** 将调用方监控上下文传递到 Provider 虚拟线程，保证用量账本和链路指标不丢失。 */
    private ChatResponse invokeWithMonitorContext(MonitorContext capturedContext,
                                                  Supplier<ChatResponse> invocation) {
        MonitorContext previousContext = MonitorContextHolder.getContext();
        try {
            if (capturedContext == null) {
                MonitorContextHolder.clearContext();
            } else {
                MonitorContextHolder.setContext(capturedContext);
            }
            return invocation.get();
        } finally {
            if (previousContext == null) {
                MonitorContextHolder.clearContext();
            } else {
                MonitorContextHolder.setContext(previousContext);
            }
        }
    }

    private RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("chat model provider call terminated unexpectedly", failure);
    }

    private List<Integer> candidateOrder() {
        List<Integer> order = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            order.add(index);
        }
        return order;
    }

    /** 创建包含{@code Suppressed}的新对象。 */
    private RuntimeException withSuppressed(RuntimeException terminal,
                                            List<RuntimeException> failures) {
        for (RuntimeException failure : failures) {
            if (failure != terminal) {
                terminal.addSuppressed(failure);
            }
        }
        return terminal;
    }

    private long deadlineNanos() {
        if (totalTimeout == null) {
            return Long.MAX_VALUE;
        }
        long now = nanoTime.getAsLong();
        long timeoutNanos = totalTimeout.toNanos();
        return now > Long.MAX_VALUE - timeoutNanos ? Long.MAX_VALUE : now + timeoutNanos;
    }

    private boolean deadlineReached(long deadlineNanos) {
        return deadlineNanos != Long.MAX_VALUE && nanoTime.getAsLong() >= deadlineNanos;
    }

    private long remainingNanos(long deadlineNanos) {
        long remaining = deadlineNanos - nanoTime.getAsLong();
        return remaining > 0L ? remaining : 0L;
    }

    private TimeoutException timeout(List<RuntimeException> failures) {
        TimeoutException timeout = new TimeoutException("chat model failover wall-clock timeout exceeded");
        for (RuntimeException failure : failures) {
            timeout.addSuppressed(failure);
        }
        return timeout;
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

    /** 区分本模块强制结束的总预算超时与 Provider 自身抛出的可故障转移超时。 */
    private static final class InFlightTimeoutException extends RuntimeException {
    }
}
