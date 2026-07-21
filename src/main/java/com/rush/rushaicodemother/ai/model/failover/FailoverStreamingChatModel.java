package com.rush.rushaicodemother.ai.model.failover;

import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
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

/**
 * Streaming failover that retries another model only before any user-visible output is emitted.
 */
public final class FailoverStreamingChatModel implements StreamingChatModel {

    private final List<AiModelCandidate<StreamingChatModel>> candidates;
    private final AiModelMetricsCollector metrics;
    private final Runnable beforeProviderAttempt;

    public FailoverStreamingChatModel(List<AiModelCandidate<StreamingChatModel>> candidates,
                                      AiModelMetricsCollector metrics) {
        this(candidates, metrics, () -> { });
    }

    public FailoverStreamingChatModel(List<AiModelCandidate<StreamingChatModel>> candidates,
                                      AiModelMetricsCollector metrics,
                                      Runnable beforeProviderAttempt) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("at least one streaming model candidate is required");
        }
        this.candidates = List.copyOf(candidates);
        this.metrics = metrics;
        this.beforeProviderAttempt = beforeProviderAttempt == null ? () -> { } : beforeProviderAttempt;
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

    private void execute(ChatRequest request,
                         ChatRequestOptions options,
                         StreamingChatResponseHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("streaming response handler is required");
        }
        AttemptState state = new AttemptState(handler);
        executeAttempt(0, request, options, state);
    }

    private void executeAttempt(int index,
                                ChatRequest request,
                                ChatRequestOptions options,
                                AttemptState state) {
        AiModelCandidate<StreamingChatModel> candidate = candidates.get(index);
        AttemptGuard attempt = new AttemptGuard();
        StreamingChatResponseHandler forwarding = forwardingHandler(
                index, request, options, state, candidate, attempt);
        try {
            beforeProviderAttempt.run();
            if (options == null) {
                candidate.model().chat(request, forwarding);
            } else {
                candidate.model().chat(request, options, forwarding);
            }
        } catch (RuntimeException failure) {
            AttemptFailure attemptFailure = attempt.finishFailure();
            if (attemptFailure != null) {
                handleFailure(index, request, options, state, candidate,
                        attemptFailure.outputObserved(), failure);
            }
        }
    }

    private StreamingChatResponseHandler forwardingHandler(
            int index,
            ChatRequest request,
            ChatRequestOptions options,
            AttemptState state,
            AiModelCandidate<StreamingChatModel> candidate,
            AttemptGuard attempt) {
        return new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                attempt.forward(() -> state.downstream.onPartialResponse(partialResponse));
            }

            @Override
            public void onPartialResponse(PartialResponse partialResponse,
                                          PartialResponseContext context) {
                attempt.forward(() -> state.downstream.onPartialResponse(partialResponse, context));
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                attempt.forward(() -> state.downstream.onPartialThinking(partialThinking));
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking,
                                          PartialThinkingContext context) {
                attempt.forward(() -> state.downstream.onPartialThinking(partialThinking, context));
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall) {
                attempt.forward(() -> state.downstream.onPartialToolCall(partialToolCall));
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall,
                                          PartialToolCallContext context) {
                attempt.forward(() -> state.downstream.onPartialToolCall(partialToolCall, context));
            }

            @Override
            public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                attempt.forward(() -> state.downstream.onCompleteToolCall(completeToolCall));
            }

            @Override
            public void onUnmappedRawEvent(Object event) {
                attempt.forward(() -> state.downstream.onUnmappedRawEvent(event));
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                if (attempt.finish()
                        && state.terminal.compareAndSet(false, true)) {
                    state.downstream.onCompleteResponse(completeResponse);
                }
            }

            @Override
            public void onError(Throwable error) {
                AttemptFailure attemptFailure = attempt.finishFailure();
                if (attemptFailure != null) {
                    handleFailure(index, request, options, state, candidate,
                            attemptFailure.outputObserved(), error);
                }
            }
        };
    }

    private void handleFailure(int index,
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
                && index + 1 < candidates.size();
        if (canFailOver) {
            AiModelCandidate<StreamingChatModel> next = candidates.get(index + 1);
            recordFailover(candidate, next, decision.category());
            executeAttempt(index + 1, request, options, state);
            return;
        }
        if (state.terminal.compareAndSet(false, true)) {
            state.downstream.onError(withSuppressed(failure, state.failures));
        }
    }

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

    private static final class AttemptState {
        private final StreamingChatResponseHandler downstream;
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private final List<Throwable> failures = new CopyOnWriteArrayList<>();

        private AttemptState(StreamingChatResponseHandler downstream) {
            this.downstream = downstream;
        }
    }

    private static final class AttemptGuard {
        private boolean finished;
        private boolean outputObserved;

        private synchronized void forward(Runnable callback) {
            if (finished) {
                return;
            }
            outputObserved = true;
            callback.run();
        }

        private synchronized boolean finish() {
            if (finished) {
                return false;
            }
            finished = true;
            return true;
        }

        private synchronized AttemptFailure finishFailure() {
            if (finished) {
                return null;
            }
            finished = true;
            return new AttemptFailure(outputObserved);
        }
    }

    private record AttemptFailure(boolean outputObserved) {
    }
}
