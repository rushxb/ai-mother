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
import dev.langchain4j.model.chat.listener.ChatModelListener;
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

    /**
 * 返回HTTP客户端{@code Builder}。
 *
 * @return AI{@code Streaming}调用运行时
 */
    public HttpClientBuilder httpClientBuilder() {
        return SpringRestClient.builder()
                .streamingRequestExecutor(requestExecutor)
                .createDefaultStreamingRequestExecutor(false);
    }

    /**
 * 返回容量{@code Controlled}。
 *
 * @param provider 提供方
 * @param modelId 模型编号
 * @param maxOutputTokens 最大输出令牌
 * @param delegate 被包装的委托对象
 * @param capacityGuard 容量防护
 * @param upstreamTimeout 上游调用超时时间
 * @return AI{@code Streaming}调用运行时
 */
    public StreamingChatModel capacityControlled(String provider,
                                                 String modelId,
                                                 int maxOutputTokens,
                                                 StreamingChatModel delegate,
                                                 AiModelCapacityGuard capacityGuard,
                                                 Duration upstreamTimeout,
                                                 ChatModelListener invocationListener) {
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
                timeoutMonitor,
                invocationListener
        );
    }

    public GenerationModelInvocationCancellationBridge cancellationBridge() {
        return cancellationBridge;
    }
}
