package com.rush.rushaicodemother.ai.model.capacity;

import com.rush.rushaicodemother.core.handler.GenerationCancellationAwareStreamingHandler;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.monitor.AiModelTimeoutMonitor;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCallTimeoutException;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCancellationScope;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelTimeoutScheduler;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatRequestOptions;
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
import java.util.concurrent.atomic.AtomicReference;

/** 持有分布式容量许可，直到一个具体的流媒体提供商调用终止。 */
public final class CapacityControlledStreamingChatModel implements StreamingChatModel {

    private final String provider;
    private final String modelId;
    private final int configuredMaxOutputTokens;
    private final StreamingChatModel delegate;
    private final AiModelCapacityGuard capacityGuard;
    private final Duration upstreamTimeout;
    private final Duration firstSignalTimeout;
    private final GenerationModelInvocationCancellationBridge cancellationBridge;
    private final GenerationModelTimeoutScheduler timeoutScheduler;
    private final AiModelTimeoutMonitor timeoutMonitor;
    private final ThreadLocal<CancellationRelay> cancellationRelay = new ThreadLocal<>();

    public CapacityControlledStreamingChatModel(String provider,
                                                String modelId,
                                                int configuredMaxOutputTokens,
                                                StreamingChatModel delegate,
                                                AiModelCapacityGuard capacityGuard) {
        this(provider, modelId, configuredMaxOutputTokens, delegate, capacityGuard, null,
                null, null, null, null);
    }

    public CapacityControlledStreamingChatModel(String provider,
                                                String modelId,
                                                int configuredMaxOutputTokens,
                                                StreamingChatModel delegate,
                                                AiModelCapacityGuard capacityGuard,
                                                Duration upstreamTimeout) {
        this(provider, modelId, configuredMaxOutputTokens, delegate, capacityGuard, upstreamTimeout,
                null, null, null, null);
    }

    public CapacityControlledStreamingChatModel(
            String provider,
            String modelId,
            int configuredMaxOutputTokens,
            StreamingChatModel delegate,
            AiModelCapacityGuard capacityGuard,
            Duration upstreamTimeout,
            Duration firstSignalTimeout,
            GenerationModelInvocationCancellationBridge cancellationBridge,
            GenerationModelTimeoutScheduler timeoutScheduler,
            AiModelTimeoutMonitor timeoutMonitor) {
        if (provider == null || provider.isBlank() || modelId == null || modelId.isBlank()
                || configuredMaxOutputTokens <= 0) {
            throw new IllegalArgumentException("capacity-controlled streaming model identity is invalid");
        }
        if (upstreamTimeout != null && (upstreamTimeout.isZero() || upstreamTimeout.isNegative())) {
            throw new IllegalArgumentException("capacity-controlled streaming model timeout must be positive");
        }
        if (firstSignalTimeout != null
                && (firstSignalTimeout.isZero() || firstSignalTimeout.isNegative())) {
            throw new IllegalArgumentException("首信号超时时间必须大于 0");
        }
        boolean supervisionPartiallyConfigured = firstSignalTimeout != null
                || cancellationBridge != null || timeoutScheduler != null || timeoutMonitor != null;
        boolean supervisionFullyConfigured = firstSignalTimeout != null
                && cancellationBridge != null && timeoutScheduler != null && timeoutMonitor != null;
        if (supervisionPartiallyConfigured && !supervisionFullyConfigured) {
            throw new IllegalArgumentException("物理模型首信号监督配置不完整");
        }
        this.provider = provider;
        this.modelId = modelId;
        this.configuredMaxOutputTokens = configuredMaxOutputTokens;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.capacityGuard = Objects.requireNonNull(capacityGuard, "capacityGuard");
        this.upstreamTimeout = upstreamTimeout;
        this.firstSignalTimeout = firstSignalTimeout;
        this.cancellationBridge = cancellationBridge;
        this.timeoutScheduler = timeoutScheduler;
        this.timeoutMonitor = timeoutMonitor;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        chat(request, ChatRequestOptions.EMPTY, handler);
    }

    @Override
    public void chat(ChatRequest request,
                     ChatRequestOptions options,
                     StreamingChatResponseHandler handler) {
        Objects.requireNonNull(handler, "流式响应处理器不能为空");
        CancellationRelay relay = new CancellationRelay();
        GenerationModelInvocationCancellationBridge.ScopeBinding scopeBinding =
                cancellationBridge == null ? null : cancellationBridge.bind(request);
        boolean cancellationAware = handler instanceof GenerationCancellationAwareStreamingHandler;
        GenerationCancellationAwareStreamingHandler.registerIfSupported(handler, relay);
        if (!cancellationAware && scopeBinding != null && scopeBinding.scope() != null) {
            scopeBinding.scope().register(relay);
        }
        CancellationRelay previous = cancellationRelay.get();
        cancellationRelay.set(relay);
        try {
            StreamingChatModel.super.chat(request, options, handler);
        } finally {
            if (previous == null) {
                cancellationRelay.remove();
            } else {
                cancellationRelay.set(previous);
            }
            if (scopeBinding != null) {
                scopeBinding.close();
            }
        }
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("streaming response handler is required");
        }
        AiModelCapacityGuard.Lease lease = capacityGuard.acquire(
                provider, modelId, configuredMaxOutputTokens, request, upstreamTimeout);
        LeaseBoundHandler forwarding = new LeaseBoundHandler(
                handler,
                lease,
                provider,
                modelId,
                firstSignalTimeout,
                timeoutScheduler,
                timeoutMonitor
        );
        try {
            lease.onLost(forwarding::onLeaseLost);
            CancellationRelay relay = cancellationRelay.get();
            if (relay != null) {
                relay.bind(forwarding);
            }
            if (!lease.isValid() || forwarding.isTerminal()) {
                return;
            }
            forwarding.startFirstSignalTimer();
            if (cancellationBridge == null) {
                delegate.doChat(request, forwarding);
            } else {
                try (GenerationModelInvocationCancellationBridge.ScopeBinding ignored =
                             cancellationBridge.activate(forwarding.transportScope())) {
                    delegate.doChat(request, forwarding);
                }
            }
        } catch (RuntimeException synchronousFailure) {
            if (forwarding.onSynchronousFailure()) {
                throw synchronousFailure;
            }
        }
    }

    private static final class CancellationRelay implements GenerationCancellationHandle {

        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<GenerationCancellationHandle> delegate = new AtomicReference<>();

        private void bind(GenerationCancellationHandle cancellationHandle) {
            Objects.requireNonNull(cancellationHandle, "容量租约取消句柄不能为空");
            if (cancelled.get()) {
                cancellationHandle.cancel();
                return;
            }
            GenerationCancellationHandle previous = delegate.getAndSet(cancellationHandle);
            if (cancelled.get() && delegate.compareAndSet(cancellationHandle, null)) {
                cancellationHandle.cancel();
            } else if (previous != null && previous != cancellationHandle) {
                previous.cancel();
            }
        }

        @Override
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            GenerationCancellationHandle active = delegate.getAndSet(null);
            if (active != null) {
                active.cancel();
            }
        }
    }

    private static final class LeaseBoundHandler
            implements StreamingChatResponseHandler, GenerationCancellationHandle {

        private final StreamingChatResponseHandler downstream;
        private final AiModelCapacityGuard.Lease lease;
        private final String provider;
        private final String modelId;
        private final Duration firstSignalTimeout;
        private final GenerationModelTimeoutScheduler timeoutScheduler;
        private final AiModelTimeoutMonitor timeoutMonitor;
        private final GenerationModelCancellationScope transportScope =
                new GenerationModelCancellationScope();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicReference<GenerationCancellationHandle> firstSignalTimer =
                new AtomicReference<>();
        private final Map<StreamingHandle, StreamingHandle> leaseAwareHandles =
                Collections.synchronizedMap(new IdentityHashMap<>());

        private LeaseBoundHandler(StreamingChatResponseHandler downstream,
                                  AiModelCapacityGuard.Lease lease,
                                  String provider,
                                  String modelId,
                                  Duration firstSignalTimeout,
                                  GenerationModelTimeoutScheduler timeoutScheduler,
                                  AiModelTimeoutMonitor timeoutMonitor) {
            this.downstream = downstream;
            this.lease = lease;
            this.provider = provider;
            this.modelId = modelId;
            this.firstSignalTimeout = firstSignalTimeout;
            this.timeoutScheduler = timeoutScheduler;
            this.timeoutMonitor = timeoutMonitor;
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
            markFirstSignal();
            terminate(() -> downstream.onCompleteResponse(completeResponse), false);
        }

        @Override
        public void onError(Throwable error) {
            terminate(() -> downstream.onError(error), false);
        }

        @Override
        public void cancel() {
            terminate(() -> { }, true);
        }

        private boolean isTerminal() {
            return terminal.get();
        }

        private GenerationModelCancellationScope transportScope() {
            return transportScope;
        }

        private void startFirstSignalTimer() {
            if (timeoutScheduler == null || firstSignalTimeout == null || terminal.get()) {
                return;
            }
            GenerationCancellationHandle timer = timeoutScheduler.schedule(
                    firstSignalTimeout, this::onFirstSignalTimeout);
            GenerationCancellationHandle previous = firstSignalTimer.getAndSet(timer);
            if (previous != null) {
                previous.cancel();
            }
            if (terminal.get() && firstSignalTimer.compareAndSet(timer, null)) {
                timer.cancel();
            }
        }

        private void forward(Runnable callback) {
            if (terminal.get()) {
                return;
            }
            markFirstSignal();
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
            cancelFirstSignalTimer();
            cancelProviderHandles();
            transportScope.cancel();
            lease.close();
            return true;
        }

        private void terminate(Runnable terminalCallback, boolean cancelProvider) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            cancelFirstSignalTimer();
            if (cancelProvider) {
                cancelProviderHandles();
                transportScope.cancel();
            } else {
                transportScope.complete();
            }
            lease.close();
            terminalCallback.run();
        }

        private void onFirstSignalTimeout() {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            firstSignalTimer.set(null);
            GenerationModelCallTimeoutException failure =
                    new GenerationModelCallTimeoutException("first-signal");
            timeoutMonitor.record(provider, modelId, failure);
            cancelProviderHandles();
            transportScope.cancel();
            lease.close();
            downstream.onError(failure);
        }

        private void markFirstSignal() {
            cancelFirstSignalTimer();
        }

        private void cancelFirstSignalTimer() {
            GenerationCancellationHandle timer = firstSignalTimer.getAndSet(null);
            if (timer != null) {
                timer.cancel();
            }
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
                    // 下游终端租赁损失仍须回调。
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
