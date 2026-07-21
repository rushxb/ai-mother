package com.rush.rushaicodemother.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.ai.provenance.AiModelProvenanceFactory;
import com.rush.rushaicodemother.model.enums.GenerationModelCallStatus;
import com.rush.rushaicodemother.model.enums.GenerationModelUsageSource;
import com.rush.rushaicodemother.service.aimodel.AiModelCircuitBreaker;
import com.rush.rushaicodemother.service.trace.GenerationModelCallCommand;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiModelMonitorListenerTest {

    private AiModelMetricsCollector metrics;
    private GenerationTraceService traceService;
    private AiModelCircuitBreaker circuitBreaker;
    private ChatModelListener listener;

    @BeforeEach
    void setUp() {
        metrics = mock(AiModelMetricsCollector.class);
        traceService = mock(GenerationTraceService.class);
        circuitBreaker = mock(AiModelCircuitBreaker.class);
        AiModelMonitorListener monitor = new AiModelMonitorListener(
                metrics,
                traceService,
                circuitBreaker,
                new AiModelProvenanceFactory(new ObjectMapper())
        );
        listener = monitor.forModel("xiaomi", "mimo-v2-flash");
        MonitorContextHolder.setContext(MonitorContext.builder()
                .userId("2")
                .appId("1")
                .taskId("task-1")
                .build());
    }

    @AfterEach
    void tearDown() {
        MonitorContextHolder.clearContext();
    }

    @Test
    void successfulInvocationMustPersistProviderAndContentAddressedLineage() {
        ChatRequest request = request();
        Map<Object, Object> attributes = new HashMap<>();
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));
        MonitorContextHolder.clearContext();

        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("done"))
                .id("provider-response-1")
                .modelName("provider-reported-model")
                .tokenUsage(new TokenUsage(11, 7, 18))
                .finishReason(FinishReason.STOP)
                .build();
        listener.onResponse(new ChatModelResponseContext(
                response, request, ModelProvider.OPEN_AI, attributes));

        ArgumentCaptor<GenerationModelCallCommand> captor =
                ArgumentCaptor.forClass(GenerationModelCallCommand.class);
        verify(traceService).recordModelCall(captor.capture());
        GenerationModelCallCommand call = captor.getValue();
        assertEquals("xiaomi", call.provider());
        assertEquals("mimo-v2-flash", call.model());
        assertEquals(GenerationModelCallStatus.SUCCESS, call.status());
        assertEquals("provider-response-1", call.providerRequestId());
        assertEquals(GenerationModelUsageSource.OFFICIAL, call.usageSource());
        assertEquals(18, call.totalTokens());
        assertNull(call.errorCategory());
        assertNotNull(call.provenance());
        assertEquals(64, call.provenance().requestHash().length());
        verify(circuitBreaker).recordSuccess("xiaomi", "mimo-v2-flash");
    }

    @Test
    void failedInvocationMustBePersistedWithoutFabricatedTokenUsage() {
        ChatRequest request = request();
        Map<Object, Object> attributes = new HashMap<>();
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));
        MonitorContextHolder.clearContext();
        RuntimeException failure = new RuntimeException("429 rate limit");

        listener.onError(new ChatModelErrorContext(
                failure, request, ModelProvider.OPEN_AI, attributes));

        ArgumentCaptor<GenerationModelCallCommand> captor =
                ArgumentCaptor.forClass(GenerationModelCallCommand.class);
        verify(traceService).recordModelCall(captor.capture());
        GenerationModelCallCommand call = captor.getValue();
        assertEquals(GenerationModelCallStatus.ERROR, call.status());
        assertEquals(GenerationModelUsageSource.UNAVAILABLE, call.usageSource());
        assertEquals("model_rate_limit", call.errorCategory());
        assertNull(call.promptTokens());
        assertNull(call.completionTokens());
        assertNull(call.totalTokens());
        verify(circuitBreaker).recordFailure("xiaomi", "mimo-v2-flash", failure);
    }

    @Test
    void provenancePersistenceFailureMustNotFailTheModelResponse() {
        ChatRequest request = request();
        Map<Object, Object> attributes = new HashMap<>();
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("done"))
                .modelName("mimo-v2-flash")
                .tokenUsage(new TokenUsage(1, 1, 2))
                .finishReason(FinishReason.STOP)
                .build();
        doThrow(new IllegalStateException("database unavailable"))
                .when(traceService).recordModelCall(any());

        assertDoesNotThrow(() -> listener.onResponse(new ChatModelResponseContext(
                response, request, ModelProvider.OPEN_AI, attributes)));

        verify(metrics).recordTracePersistenceFailure(
                "xiaomi", "mimo-v2-flash", "success");
    }

    private ChatRequest request() {
        return ChatRequest.builder()
                .messages(SystemMessage.from("system-v1"), UserMessage.from("build a dashboard"))
                .modelName("mimo-v2-flash")
                .temperature(0.3)
                .build();
    }
}
