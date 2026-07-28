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

    /**
 * 创建容量{@code Controlled}对话模型实例并完成必要的依赖和初始状态设置。
 *
 * @param provider 提供方
 * @param modelId 模型编号
 * @param configuredMaxOutputTokens 已配置最大输出令牌
 * @param delegate 被包装的委托对象
 * @param capacityGuard 容量防护
 * @param upstreamTimeout 上游调用超时时间
 */
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

    /**
 * 返回{@code do}对话。
 *
 * @param request 请求参数
 * @return 容量{@code Controlled}对话模型
 */
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
