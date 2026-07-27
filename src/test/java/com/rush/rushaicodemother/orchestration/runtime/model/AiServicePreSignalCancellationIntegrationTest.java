package com.rush.rushaicodemother.orchestration.runtime.model;

import com.rush.rushaicodemother.ai.model.capacity.AiModelCapacityGuard;
import com.rush.rushaicodemother.ai.model.capacity.CapacityControlledStreamingChatModel;
import com.rush.rushaicodemother.monitor.AiModelTimeoutMonitor;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AiServicePreSignalCancellationIntegrationTest {

    @Test
    void aiServiceInternalHandlerMustNotBlockPreSignalCancellation() {
        GenerationModelInvocationCancellationBridge bridge =
                new GenerationModelInvocationCancellationBridge();
        GenerationModelCancellationScope scope = new GenerationModelCancellationScope();
        AtomicBoolean providerStarted = new AtomicBoolean();
        AtomicInteger transportCancellations = new AtomicInteger();
        AtomicInteger leaseReleases = new AtomicInteger();
        AiModelCapacityGuard capacityGuard = (provider, modelId, maxTokens, request) ->
                leaseReleases::incrementAndGet;
        StreamingChatModel silentProvider = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                providerStarted.set(true);
                bridge.registerTransportCancellation(transportCancellations::incrementAndGet);
            }
        };
        GenerationModelTimeoutScheduler scheduler = (delay, action) -> () -> { };
        StreamingChatModel model = new CapacityControlledStreamingChatModel(
                "provider-a",
                "model-a",
                1024,
                silentProvider,
                capacityGuard,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                bridge,
                scheduler,
                mock(AiModelTimeoutMonitor.class)
        );
        StreamingAssistant assistant = AiServices.builder(StreamingAssistant.class)
                .streamingChatModel(model)
                .registerListener(bridge.requestIssuedListener())
                .build();
        InvocationParameters parameters = InvocationParameters.from(
                GenerationModelCancellationScope.INVOCATION_PARAMETER, scope);
        AtomicInteger callbacks = new AtomicInteger();
        TokenStream stream = assistant.chat("生成一个页面", parameters)
                .onCompleteResponse(ignored -> callbacks.incrementAndGet())
                .onError(ignored -> callbacks.incrementAndGet());

        stream.start();
        assertTrue(providerStarted.get());
        assertEquals(0, callbacks.get());

        scope.cancel();

        assertEquals(1, transportCancellations.get());
        assertEquals(1, leaseReleases.get());
        assertEquals(0, callbacks.get());
    }

    private interface StreamingAssistant {

        @UserMessage("{{message}}")
        TokenStream chat(@V("message") String message, InvocationParameters parameters);
    }
}
