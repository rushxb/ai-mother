package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.ai.model.capacity.AiModelCapacityGuard;
import com.rush.rushaicodemother.ai.model.capacity.CapacityControlledStreamingChatModel;
import com.rush.rushaicodemother.ai.model.transport.CancellableAiStreamingRequestExecutor;
import com.rush.rushaicodemother.monitor.AiModelTimeoutMonitor;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelTimeoutPolicy;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelTimeoutScheduler;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.spring.restclient.SpringRestClient;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 组装流式模型所需的取消、传输和首信号监督能力。 */
@Component
@RequiredArgsConstructor
public class AiStreamingCallRuntime {

    private final GenerationModelInvocationCancellationBridge cancellationBridge;
    private final CancellableAiStreamingRequestExecutor requestExecutor;
    private final GenerationModelTimeoutScheduler timeoutScheduler;
    private final AiModelTimeoutMonitor timeoutMonitor;
    private final GenerationModelTimeoutPolicy timeoutPolicy;

    public HttpClientBuilder httpClientBuilder() {
        return SpringRestClient.builder()
                .streamingRequestExecutor(requestExecutor)
                .createDefaultStreamingRequestExecutor(false);
    }

    public StreamingChatModel capacityControlled(String provider,
                                                 String modelId,
                                                 int maxOutputTokens,
                                                 StreamingChatModel delegate,
                                                 AiModelCapacityGuard capacityGuard,
                                                 Duration upstreamTimeout) {
        return new CapacityControlledStreamingChatModel(
                provider,
                modelId,
                maxOutputTokens,
                delegate,
                capacityGuard,
                upstreamTimeout,
                timeoutPolicy.firstSignalTimeout(upstreamTimeout),
                cancellationBridge,
                timeoutScheduler,
                timeoutMonitor
        );
    }

    public GenerationModelInvocationCancellationBridge cancellationBridge() {
        return cancellationBridge;
    }
}
