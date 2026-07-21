package com.rush.rushaicodemother.ai.model.capacity;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
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
import dev.langchain4j.model.chat.response.StreamingHandle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Holds a distributed capacity permit until one concrete streaming provider call terminates. */
public final class CapacityControlledStreamingChatModel implements StreamingChatModel {

    private final String provider;
    private final String modelId;
    private final int configuredMaxOutputTokens;
    private final StreamingChatModel delegate;
    private final AiModelCapacityGuard capacityGuard;
    private final Duration upstreamTimeout;

    public CapacityControlledStreamingChatModel(String provider,
                                                String modelId,
                                                int configuredMaxOutputTokens,
                                                StreamingChatModel delegate,
                                                AiModelCapacityGuard capacityGuard) {
        this(provider, modelId, configuredMaxOutputTokens, delegate, capacityGuard, null);
    }

    public CapacityControlledStreamingChatModel(String provider,
                                                String modelId,
                                                int configuredMaxOutputTokens,
                                                StreamingChatModel delegate,
                                                AiModelCapacityGuard capacityGuard,
                                                Duration upstreamTimeout) {
        if (provider == null || provider.isBlank() || modelId == null || modelId.isBlank()
                || configuredMaxOutputTokens <= 0) {
            throw new IllegalArgumentException("capacity-controlled streaming model identity is invalid");
        }
        if (upstreamTimeout != null && (upstreamTimeout.isZero() || upstreamTimeout.isNegative())) {
            throw new IllegalArgumentException("capacity-controlled streaming model timeout must be positive");
        }
        this.provider = provider;
        this.modelId = modelId;
        this.configuredMaxOutputTokens = configuredMaxOutputTokens;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.capacityGuard = Objects.requireNonNull(capacityGuard, "capacityGuard");
        this.upstreamTimeout = upstreamTimeout;
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("streaming response handler is required");
        }
        AiModelCapacityGuard.Lease lease = capacityGuard.acquire(
                provider, modelId, configuredMaxOutputTokens, request, upstreamTimeout);
        LeaseBoundHandler forwarding = new LeaseBoundHandler(handler, lease);
        try {
            lease.onLost(forwarding::onLeaseLost);
            if (!lease.isValid()) {
                return;
            }
            delegate.doChat(request, forwarding);
        } catch (RuntimeException synchronousFailure) {
            if (forwarding.onSynchronousFailure()) {
                throw synchronousFailure;
            }
        }
    }

    private static final class LeaseBoundHandler implements StreamingChatResponseHandler {

        private final StreamingChatResponseHandler downstream;
        private final AiModelCapacityGuard.Lease lease;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final Map<StreamingHandle, StreamingHandle> leaseAwareHandles =
                Collections.synchronizedMap(new IdentityHashMap<>());

        private LeaseBoundHandler(StreamingChatResponseHandler downstream,
                                  AiModelCapacityGuard.Lease lease) {
            this.downstream = downstream;
            this.lease = lease;
        }

        @Override
        public void onPartialResponse(String partialResponse) {
            forward(() -> downstream.onPartialResponse(partialResponse));
        }

        @Override
        public void onPartialResponse(PartialResponse partialResponse,
                                      PartialResponseContext context) {
            forward(() -> downstream.onPartialResponse(
                    partialResponse, responseContext(context)));
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking) {
            forward(() -> downstream.onPartialThinking(partialThinking));
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking,
                                      PartialThinkingContext context) {
            forward(() -> downstream.onPartialThinking(
                    partialThinking, thinkingContext(context)));
        }

        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall) {
            forward(() -> downstream.onPartialToolCall(partialToolCall));
        }

        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall,
                                      PartialToolCallContext context) {
            forward(() -> downstream.onPartialToolCall(
                    partialToolCall, toolCallContext(context)));
        }

        @Override
        public void onCompleteToolCall(CompleteToolCall completeToolCall) {
            forward(() -> downstream.onCompleteToolCall(completeToolCall));
        }

        @Override
        public void onUnmappedRawEvent(Object event) {
            forward(() -> downstream.onUnmappedRawEvent(event));
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            terminate(() -> downstream.onCompleteResponse(completeResponse), false);
        }

        @Override
        public void onError(Throwable error) {
            terminate(() -> downstream.onError(error), false);
        }

        private void forward(Runnable callback) {
            if (terminal.get()) {
                return;
            }
            try {
                callback.run();
            } catch (RuntimeException callbackFailure) {
                terminate(() -> { }, true);
                throw callbackFailure;
            }
        }

        private void onLeaseLost() {
            terminate(
                    () -> downstream.onError(AiModelCapacityException.unavailable(null)),
                    true
            );
        }

        private boolean onSynchronousFailure() {
            if (!terminal.compareAndSet(false, true)) {
                return false;
            }
            cancelProviderHandles();
            lease.close();
            return true;
        }

        private void terminate(Runnable terminalCallback, boolean cancelProvider) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            if (cancelProvider) {
                cancelProviderHandles();
            }
            lease.close();
            terminalCallback.run();
        }

        private PartialResponseContext responseContext(PartialResponseContext context) {
            if (context == null || context.streamingHandle() == null) {
                return context;
            }
            return new PartialResponseContext(leaseAware(context.streamingHandle()));
        }

        private PartialThinkingContext thinkingContext(PartialThinkingContext context) {
            if (context == null || context.streamingHandle() == null) {
                return context;
            }
            return new PartialThinkingContext(leaseAware(context.streamingHandle()));
        }

        private PartialToolCallContext toolCallContext(PartialToolCallContext context) {
            if (context == null || context.streamingHandle() == null) {
                return context;
            }
            return new PartialToolCallContext(leaseAware(context.streamingHandle()));
        }

        private StreamingHandle leaseAware(StreamingHandle handle) {
            synchronized (leaseAwareHandles) {
                return leaseAwareHandles.computeIfAbsent(handle, ignored -> new StreamingHandle() {
                    @Override
                    public void cancel() {
                        try {
                            handle.cancel();
                        } finally {
                            terminate(() -> { }, false);
                        }
                    }

                    @Override
                    public boolean isCancelled() {
                        return handle.isCancelled();
                    }
                });
            }
        }

        private void cancelProviderHandles() {
            List<StreamingHandle> handles;
            synchronized (leaseAwareHandles) {
                handles = new ArrayList<>(leaseAwareHandles.keySet());
            }
            for (StreamingHandle handle : handles) {
                try {
                    handle.cancel();
                } catch (RuntimeException ignored) {
                    // Lease loss must still reach the downstream terminal callback.
                }
            }
        }
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }
}
