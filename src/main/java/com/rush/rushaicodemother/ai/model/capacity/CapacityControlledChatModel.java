package com.rush.rushaicodemother.ai.model.capacity;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 针对一个具体的同步提供者/候选模型应用容量准入。 */
public final class CapacityControlledChatModel implements ChatModel {

    private final String provider;
    private final String modelId;
    private final int configuredMaxOutputTokens;
    private final ChatModel delegate;
    private final AiModelCapacityGuard capacityGuard;
    private final Duration upstreamTimeout;

    public CapacityControlledChatModel(String provider,
                                       String modelId,
                                       int configuredMaxOutputTokens,
                                       ChatModel delegate,
                                       AiModelCapacityGuard capacityGuard) {
        this(provider, modelId, configuredMaxOutputTokens, delegate, capacityGuard, null);
    }

    public CapacityControlledChatModel(String provider,
                                       String modelId,
                                       int configuredMaxOutputTokens,
                                       ChatModel delegate,
                                       AiModelCapacityGuard capacityGuard,
                                       Duration upstreamTimeout) {
        if (provider == null || provider.isBlank() || modelId == null || modelId.isBlank()
                || configuredMaxOutputTokens <= 0) {
            throw new IllegalArgumentException("capacity-controlled chat model identity is invalid");
        }
        if (upstreamTimeout != null && (upstreamTimeout.isZero() || upstreamTimeout.isNegative())) {
            throw new IllegalArgumentException("capacity-controlled chat model timeout must be positive");
        }
        this.provider = provider;
        this.modelId = modelId;
        this.configuredMaxOutputTokens = configuredMaxOutputTokens;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.capacityGuard = Objects.requireNonNull(capacityGuard, "capacityGuard");
        this.upstreamTimeout = upstreamTimeout;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        try (AiModelCapacityGuard.Lease lease = capacityGuard.acquire(
                provider, modelId, configuredMaxOutputTokens, request, upstreamTimeout)) {
            ChatResponse response = delegate.doChat(request);
            if (!lease.isValid()) {
                throw AiModelCapacityException.unavailable(null);
            }
            return response;
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
