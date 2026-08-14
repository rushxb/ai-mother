package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.ai.model.capacity.AiModelCapacityGuard;
import com.rush.rushaicodemother.ai.model.transport.CancellableAiStreamingRequestExecutor;
import com.rush.rushaicodemother.ai.model.failover.AiModelCandidate;
import com.rush.rushaicodemother.ai.model.failover.FailoverChatModel;
import com.rush.rushaicodemother.ai.model.failover.FailoverStreamingChatModel;
import com.rush.rushaicodemother.ai.model.failover.FirstTokenHedgePolicy;
import com.rush.rushaicodemother.ai.model.failover.FirstTokenHedgeScheduler;
import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import com.rush.rushaicodemother.monitor.AiModelMonitorListener;
import com.rush.rushaicodemother.monitor.AiModelTimeoutMonitor;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelTimeoutPolicy;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelTimeoutScheduler;
import com.rush.rushaicodemother.service.aimodel.AiModelProtectedSecret;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeConfiguration;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeService;
import com.rush.rushaicodemother.service.aimodel.AiModelSecretService;
import com.rush.rushaicodemother.testsupport.AiModelSecretTestFixtures;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamingModelFactoryTest {

    @Test
    void fastTasksMustReadOnlyChatRuntimeConfiguration() {
        AiModelRuntimeService runtimeService = mock(AiModelRuntimeService.class);
        when(runtimeService.listRunnableModelsByType("chat")).thenReturn(List.of(model("chat")));
        StreamingModelFactory factory = factory(runtimeService);

        ChatModel routingModel = factory.createRoutingChatModel();
        factory.createCreateSpecChatModel();
        factory.createChatModel();
        factory.createModel(GenerationPerformanceProfile.speedFirst());

        verify(runtimeService, times(4)).listRunnableModelsByType("chat");
        verify(runtimeService).listRunnableModelsByType("routing");
        // 物理调用 wrapper 手动通知 listener，不再暴露给 LangChain4j 重复回调。
        assertEquals(0, routingModel.listeners().size());
    }

    @Test
    void routingTasksMustPreferDedicatedRoutingPool() {
        AiModelRuntimeService runtimeService = mock(AiModelRuntimeService.class);
        when(runtimeService.listRunnableModelsByType("routing"))
                .thenReturn(List.of(model("routing")));
        StreamingModelFactory factory = factory(runtimeService);

        factory.createRoutingChatModel();

        verify(runtimeService).listRunnableModelsByType("routing");
        verify(runtimeService, never()).listRunnableModelsByType("chat");
    }

    @Test
    void qualityTasksMustReadOnlyReasoningRuntimeConfiguration() {
        AiModelRuntimeService runtimeService = mock(AiModelRuntimeService.class);
        when(runtimeService.listRunnableModelsByType("reasoning"))
                .thenReturn(List.of(model("reasoning")));
        StreamingModelFactory factory = factory(runtimeService);

        factory.createPrimaryChatModel();
        factory.createReasoningModel();
        factory.createModel(GenerationPerformanceProfile.qualityFirst());

        verify(runtimeService, times(3)).listRunnableModelsByType("reasoning");
    }

    @Test
    void missingRuntimeConfigurationMustFailFast() {
        AiModelRuntimeService runtimeService = mock(AiModelRuntimeService.class);
        when(runtimeService.listRunnableModelsByType("chat"))
                .thenThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "missing"));

        assertThrows(BusinessException.class, () -> factory(runtimeService).createCreateSpecChatModel());
    }

    @Test
    void requestPoolMustRespectConfiguredCandidateLimit() {
        AiModelRuntimeService runtimeService = mock(AiModelRuntimeService.class);
        AiModelRuntimeConfiguration primary = model("chat", "primary");
        AiModelRuntimeConfiguration fallback = model("chat", "fallback");
        AiModelRuntimeConfiguration ignored = model("chat", "ignored");
        when(runtimeService.listRunnableModelsByType("chat"))
                .thenReturn(List.of(primary, fallback, ignored));
        AiModelMonitorListener listener = monitorListener();
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        properties.setFailoverMaxCandidates(2);
        StreamingModelFactory factory = factory(runtimeService, listener, properties);

        ChatModel result = factory.createRoutingChatModel();

        assertInstanceOf(FailoverChatModel.class, result);
        verify(listener).forModel("xiaomi", "primary", 4096);
        verify(listener).forModel("xiaomi", "fallback", 4096);
        verify(listener, never()).forModel("xiaomi", "ignored", 4096);
    }

    @Test
    void failoverTimeoutSlicesMustNeverExceedTheCallerBudget() {
        Duration total = Duration.ofSeconds(30);

        List<Duration> slices = StreamingModelFactory.allocateTimeoutSlices(total, 4);

        assertEquals(4, slices.size());
        assertEquals(total, slices.stream().reduce(Duration.ZERO, Duration::plus));
        org.junit.jupiter.api.Assertions.assertTrue(
                slices.stream().allMatch(slice -> !slice.isZero() && !slice.isNegative()));
    }

    @Test
    void taskScopedStreamingModelMustUseTheAttemptAwareFailoverPool() {
        AiModelRuntimeService runtimeService = mock(AiModelRuntimeService.class);
        when(runtimeService.listRunnableModelsByType("chat")).thenReturn(List.of(model("chat")));
        StreamingModelFactory factory = factory(runtimeService);

        StreamingChatModel result = factory.createExecutionModel(
                GenerationPerformanceProfile.balanced(), Duration.ofSeconds(20), () -> { });

        assertInstanceOf(FailoverStreamingChatModel.class, result);
    }

    @Test
    void taskScopedCreateSpecModelMustUseAttemptAwareFailoverPool() {
        AiModelRuntimeService runtimeService = mock(AiModelRuntimeService.class);
        when(runtimeService.listRunnableModelsByType("chat")).thenReturn(List.of(model("chat")));
        StreamingModelFactory factory = factory(runtimeService);

        ChatModel result = factory.createExecutionCreateSpecChatModel(
                Duration.ofSeconds(20), () -> { }, () -> { });

        assertInstanceOf(FailoverChatModel.class, result);
    }

    @Test
    void firstTokenHedgePolicyMustRespectEnablementCandidateAndTimeoutBoundaries() {
        AiModelRuntimeService runtimeService = mock(AiModelRuntimeService.class);
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        StreamingModelFactory factory = factory(runtimeService, monitorListener(), properties);
        List<AiModelCandidate<StreamingChatModel>> candidates = List.of(
                new AiModelCandidate<>("provider-a", "model-a", mock(StreamingChatModel.class)),
                new AiModelCandidate<>("provider-b", "model-b", mock(StreamingChatModel.class))
        );

        assertFalse(factory.resolveFirstTokenHedgePolicy(
                candidates, Duration.ofSeconds(4)).enabled());

        properties.setFirstTokenHedgeEnabled(true);
        properties.setFirstTokenHedgeDelay(Duration.ofSeconds(2));
        assertFalse(factory.resolveFirstTokenHedgePolicy(
                candidates, Duration.ofSeconds(4)).enabled());
        assertFalse(factory.resolveFirstTokenHedgePolicy(
                candidates.subList(0, 1), Duration.ofSeconds(4)).enabled());

        properties.setFirstTokenHedgeDelay(Duration.ofMillis(1999));
        FirstTokenHedgePolicy enabled = factory.resolveFirstTokenHedgePolicy(
                candidates, Duration.ofSeconds(4));
        assertTrue(enabled.enabled());
        assertEquals(Duration.ofMillis(1999), enabled.delay());
    }

    @Test
    void providerClientBuilderMustResolveSecretOnlyAtFactoryBoundary() {
        AiModelRuntimeService runtimeService = mock(AiModelRuntimeService.class);
        AiModelRuntimeConfiguration runtimeModel = model("chat");
        when(runtimeService.listRunnableModelsByType("chat")).thenReturn(List.of(runtimeModel));
        AiModelSecretService secretService = spy(AiModelSecretTestFixtures.service());

        factory(runtimeService, monitorListener(), new AiModelRuntimeProperties(), secretService)
                .createChatModel();

        verify(secretService).resolve(
                runtimeModel.secretRef(), runtimeModel.secretFingerprint());
    }

    private StreamingModelFactory factory(AiModelRuntimeService runtimeService) {
        return factory(runtimeService, monitorListener(), new AiModelRuntimeProperties());
    }

    private StreamingModelFactory factory(AiModelRuntimeService runtimeService,
                                          AiModelMonitorListener listener,
                                          AiModelRuntimeProperties properties) {
        return factory(runtimeService, listener, properties, AiModelSecretTestFixtures.service());
    }

    private StreamingModelFactory factory(AiModelRuntimeService runtimeService,
                                          AiModelMonitorListener listener,
                                          AiModelRuntimeProperties properties,
                                          AiModelSecretService secretService) {
        return new StreamingModelFactory(
                listener,
                runtimeService,
                properties,
                new OpenAiThinkingPolicy(),
                mock(AiModelMetricsCollector.class),
                mock(AiModelCapacityGuard.class),
                secretService,
                mock(FirstTokenHedgeScheduler.class),
                streamingCallRuntime()
        );
    }

    private AiStreamingCallRuntime streamingCallRuntime() {
        GenerationModelInvocationCancellationBridge bridge =
                new GenerationModelInvocationCancellationBridge();
        return new AiStreamingCallRuntime(
                bridge,
                new CancellableAiStreamingRequestExecutor(bridge),
                mock(GenerationModelTimeoutScheduler.class),
                mock(AiModelTimeoutMonitor.class),
                GenerationModelTimeoutPolicy.defaults()
        );
    }

    private AiModelMonitorListener monitorListener() {
        AiModelMonitorListener listener = mock(AiModelMonitorListener.class);
        when(listener.forModel(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(listener);
        return listener;
    }

    private AiModelRuntimeConfiguration model(String modelType) {
        boolean reasoning = "reasoning".equals(modelType);
        return model(modelType, reasoning ? "mimo-v2.5-pro" : "mimo-v2-flash");
    }

    private AiModelRuntimeConfiguration model(String modelType, String modelId) {
        boolean reasoning = "reasoning".equals(modelType);
        AiModelProtectedSecret secret = AiModelSecretTestFixtures.protect("test-key");
        return new AiModelRuntimeConfiguration(
                "xiaomi",
                modelId,
                modelType,
                "https://models.example.com/v1",
                secret.reference(),
                secret.fingerprint(),
                secret.keyId(),
                4096,
                reasoning ? 1.0 : 0.3,
                reasoning
        );
    }
}
