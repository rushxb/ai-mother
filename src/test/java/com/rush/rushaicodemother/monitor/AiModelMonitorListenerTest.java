package com.rush.rushaicodemother.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.ai.provenance.AiModelProvenanceFactory;
import com.rush.rushaicodemother.model.enums.GenerationModelCallStatus;
import com.rush.rushaicodemother.model.enums.GenerationModelUsageSource;
import com.rush.rushaicodemother.model.enums.ModelInvocationBillingMode;
import com.rush.rushaicodemother.model.enums.ModelInvocationPurpose;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCallTimeoutException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelMonitorListenerTest {

    private AiModelMetricsCollector metrics;
    private GenerationTraceService traceService;
    private AiModelCircuitBreaker circuitBreaker;
    private GenerationPerformanceMonitorService performanceMonitorService;
    private AiModelInvocationObserver invocationObserver;
    private ModelTokenUsageEstimator tokenUsageEstimator;
    private ChatModelListener listener;

    @BeforeEach
    void setUp() {
        metrics = mock(AiModelMetricsCollector.class);
        traceService = mock(GenerationTraceService.class);
        circuitBreaker = mock(AiModelCircuitBreaker.class);
        invocationObserver = mock(AiModelInvocationObserver.class);
        tokenUsageEstimator = mock(ModelTokenUsageEstimator.class);
        when(tokenUsageEstimator.estimate(any(), isNull()))
                .thenReturn(new EstimatedModelTokenUsage(9, 0, 9));
        performanceMonitorService = new GenerationPerformanceMonitorService();
        performanceMonitorService.startTask("task-1", 1L, 2L, "heavy", "vue_project");
        AiModelMonitorListener monitor = new AiModelMonitorListener(
                metrics,
                traceService,
                circuitBreaker,
                new AiModelProvenanceFactory(new ObjectMapper()),
                tokenUsageEstimator,
                performanceMonitorService,
                java.util.List.of(invocationObserver)
        );
        listener = monitor.forModel("xiaomi", "mimo-v2-flash", 256);
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
        verify(invocationObserver).onRequest("xiaomi", "mimo-v2-flash");
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
        verify(traceService, times(2)).recordModelCall(captor.capture());
        assertEquals(GenerationModelCallStatus.STARTED, captor.getAllValues().getFirst().status());
        GenerationModelCallCommand call = captor.getAllValues().getLast();
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
        assertTrue(performanceMonitorService.getSummary(10).getRecentTasks().getFirst().getSpans().stream()
                .anyMatch(span -> "model_provider_attempt".equals(span.getStage())
                        && "success".equals(span.getStatus())));
    }

    @Test
    void failedInvocationMustRetainThePreflightCostEstimate() {
        ChatRequest request = request();
        Map<Object, Object> attributes = new HashMap<>();
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));
        MonitorContextHolder.clearContext();
        RuntimeException failure = new RuntimeException("429 rate limit");

        listener.onError(new ChatModelErrorContext(
                failure, request, ModelProvider.OPEN_AI, attributes));

        ArgumentCaptor<GenerationModelCallCommand> captor =
                ArgumentCaptor.forClass(GenerationModelCallCommand.class);
        verify(traceService, times(2)).recordModelCall(captor.capture());
        GenerationModelCallCommand call = captor.getAllValues().getLast();
        assertEquals(GenerationModelCallStatus.ERROR, call.status());
        assertEquals(GenerationModelUsageSource.ESTIMATED, call.usageSource());
        assertEquals("model_rate_limit", call.errorCategory());
        assertEquals(9, call.promptTokens());
        assertEquals(256, call.completionTokens());
        assertEquals(265, call.totalTokens());
        verify(circuitBreaker).recordFailure("xiaomi", "mimo-v2-flash", failure);
    }

    @ParameterizedTest
    @MethodSource("cancelAndTimeoutFailures")
    void cancellationAndTimeoutMustRetainEstimatedProviderCost(
            Throwable failure,
            String expectedCategory) {
        ChatRequest request = request();
        Map<Object, Object> attributes = new HashMap<>();
        listener.onRequest(new ChatModelRequestContext(
                request, ModelProvider.OPEN_AI, attributes));

        listener.onError(new ChatModelErrorContext(
                failure, request, ModelProvider.OPEN_AI, attributes));

        ArgumentCaptor<GenerationModelCallCommand> captor =
                ArgumentCaptor.forClass(GenerationModelCallCommand.class);
        verify(traceService, times(2)).recordModelCall(captor.capture());
        GenerationModelCallCommand terminal = captor.getAllValues().getLast();
        assertEquals(GenerationModelCallStatus.ERROR, terminal.status());
        assertEquals(GenerationModelUsageSource.ESTIMATED, terminal.usageSource());
        assertEquals(expectedCategory, terminal.errorCategory());
        assertEquals(265, terminal.totalTokens());
    }

    private static Stream<Arguments> cancelAndTimeoutFailures() {
        return Stream.of(
                Arguments.of(
                        new CancellationException("user cancelled"),
                        "model_cancelled"),
                Arguments.of(
                        new GenerationModelCallTimeoutException("first-signal"),
                        "model_timeout")
        );
    }

    @Test
    void successfulInvocationWithoutProviderUsageMustPersistConservativeEstimate() {
        ChatRequest request = request();
        Map<Object, Object> attributes = new HashMap<>();
        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("done"))
                .modelName("mimo-v2-flash")
                .finishReason(FinishReason.STOP)
                .build();
        org.mockito.Mockito.when(tokenUsageEstimator.estimate(request, response))
                .thenReturn(new EstimatedModelTokenUsage(17, 5, 22));

        listener.onResponse(new ChatModelResponseContext(
                response, request, ModelProvider.OPEN_AI, attributes));

        ArgumentCaptor<GenerationModelCallCommand> captor =
                ArgumentCaptor.forClass(GenerationModelCallCommand.class);
        verify(traceService, times(2)).recordModelCall(captor.capture());
        GenerationModelCallCommand call = captor.getAllValues().getLast();
        assertEquals(GenerationModelUsageSource.ESTIMATED, call.usageSource());
        assertEquals(17, call.promptTokens());
        assertEquals(5, call.completionTokens());
        assertEquals(22, call.totalTokens());
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
                .when(traceService).recordModelCall(argThat(
                        command -> command.status() == GenerationModelCallStatus.SUCCESS));

        assertDoesNotThrow(() -> listener.onResponse(new ChatModelResponseContext(
                response, request, ModelProvider.OPEN_AI, attributes)));

        verify(metrics).recordTracePersistenceFailure(
                "xiaomi", "mimo-v2-flash", "success");
    }

    @Test
    void providerInvocationMustFailClosedWhenStartedLedgerCannotBePersisted() {
        ChatRequest request = request();
        doThrow(new IllegalStateException("ledger unavailable"))
                .when(traceService).recordModelCall(argThat(
                        command -> command.status() == GenerationModelCallStatus.STARTED));

        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> listener.onRequest(new ChatModelRequestContext(
                        request, ModelProvider.OPEN_AI, new HashMap<>()))
        );

        assertEquals("ledger unavailable", failure.getMessage());
    }

    @Test
    void peripheralInvocationMustPersistPurposeAndExplicitExemptionWithoutAnAppId() {
        MonitorContextHolder.setContext(MonitorContext.builder()
                .userId("2")
                .taskId("prompt-optimize:550e8400-e29b-41d4-a716-446655440000")
                .invocationPurpose(ModelInvocationPurpose.PROMPT_OPTIMIZATION)
                .billingMode(ModelInvocationBillingMode.EXEMPT)
                .billingExemptionReason("interactive_free_tier")
                .build());
        ChatRequest request = request();
        Map<Object, Object> attributes = new HashMap<>();

        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));

        ArgumentCaptor<GenerationModelCallCommand> captor =
                ArgumentCaptor.forClass(GenerationModelCallCommand.class);
        verify(traceService).recordModelCall(captor.capture());
        GenerationModelCallCommand started = captor.getValue();
        assertNull(started.appId());
        assertEquals(ModelInvocationPurpose.PROMPT_OPTIMIZATION, started.invocationPurpose());
        assertEquals(ModelInvocationBillingMode.EXEMPT, started.billingMode());
        assertEquals("interactive_free_tier", started.billingExemptionReason());
    }

    @Test
    void physicalInvocationWithoutBillingContextMustFailBeforeProviderDispatch() {
        MonitorContextHolder.clearContext();

        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> listener.onRequest(new ChatModelRequestContext(
                        request(), ModelProvider.OPEN_AI, new HashMap<>()))) ;

        assertEquals("physical model invocation requires a complete billing context",
                failure.getMessage());
    }

    private ChatRequest request() {
        return ChatRequest.builder()
                .messages(SystemMessage.from("system-v1"), UserMessage.from("build a dashboard"))
                .modelName("mimo-v2-flash")
                .temperature(0.3)
                .build();
    }
}
