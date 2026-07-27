package com.rush.rushaicodemother.ai.model.failover;

import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/** 跨有限的、健康的模型池进行同步请求级故障转移。 */
public final class FailoverChatModel implements ChatModel {

    private final List<AiModelCandidate<ChatModel>> candidates;
    private final AiModelMetricsCollector metrics;
    private final Duration totalTimeout;
    private final LongSupplier nanoTime;
    private final IntConsumer beforeProviderAttempt;
    private final boolean stickyProvider;
    private final AtomicInteger preferredCandidateIndex = new AtomicInteger();

    public FailoverChatModel(List<AiModelCandidate<ChatModel>> candidates,
                             AiModelMetricsCollector metrics) {
        this(candidates, metrics, null, System::nanoTime, ignored -> { }, false);
    }

    /** 创建一个故障转移池，其中每个候选者共享一个挂钟预算。 */
    public FailoverChatModel(List<AiModelCandidate<ChatModel>> candidates,
                             AiModelMetricsCollector metrics,
                             Duration totalTimeout) {
        this(candidates, metrics, totalTimeout, System::nanoTime, ignored -> { }, false);
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
        }, true);
    }

    FailoverChatModel(List<AiModelCandidate<ChatModel>> candidates,
                      AiModelMetricsCollector metrics,
                      Duration totalTimeout,
                      LongSupplier nanoTime) {
        this(candidates, metrics, totalTimeout, nanoTime, ignored -> { }, false);
    }

    private FailoverChatModel(List<AiModelCandidate<ChatModel>> candidates,
                              AiModelMetricsCollector metrics,
                              Duration totalTimeout,
                              LongSupplier nanoTime,
                              IntConsumer beforeProviderAttempt,
                              boolean stickyProvider) {
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
        this.stickyProvider = stickyProvider;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return execute(model -> model.chat(request));
    }

    @Override
    public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        return execute(model -> model.chat(request, options));
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return candidates.getFirst().model().defaultRequestParameters();
    }

    @Override
    public ModelProvider provider() {
        return candidates.getFirst().model().provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        Set<Capability> intersection = new LinkedHashSet<>(
                candidates.getFirst().model().supportedCapabilities());
        for (int index = 1; index < candidates.size(); index++) {
            intersection.retainAll(candidates.get(index).model().supportedCapabilities());
        }
        return Set.copyOf(intersection);
    }

    private ChatResponse execute(Function<ChatModel, ChatResponse> invocation) {
        List<RuntimeException> failures = new ArrayList<>();
        long deadlineNanos = deadlineNanos();
        List<Integer> candidateOrder = candidateOrder();
        for (int attemptIndex = 0; attemptIndex < candidateOrder.size(); attemptIndex++) {
            if (attemptIndex > 0 && deadlineReached(deadlineNanos)) {
                throw timeout(failures);
            }
            int candidateIndex = candidateOrder.get(attemptIndex);
            AiModelCandidate<ChatModel> candidate = candidates.get(candidateIndex);
            try {
                beforeProviderAttempt.accept(attemptIndex);
                ChatResponse response = invocation.apply(candidate.model());
                promote(candidateIndex);
                return response;
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
            }
        }
        throw new IllegalStateException("chat model failover pool completed without a result");
    }

    private List<Integer> candidateOrder() {
        int start = stickyProvider ? preferredCandidateIndex.get() : 0;
        List<Integer> order = new ArrayList<>(candidates.size());
        for (int offset = 0; offset < candidates.size(); offset++) {
            order.add((start + offset) % candidates.size());
        }
        return order;
    }

    private void promote(int candidateIndex) {
        if (stickyProvider) {
            preferredCandidateIndex.set(candidateIndex);
        }
    }

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
}
