package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.ai.model.failover.FailoverChatModel;
import com.rush.rushaicodemother.ai.model.failover.FailoverStreamingChatModel;
import com.rush.rushaicodemother.ai.model.capacity.AiModelCapacityGuard;
import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import com.rush.rushaicodemother.monitor.AiModelMonitorListener;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals(1, routingModel.listeners().size());
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
        verify(listener).forModel("xiaomi", "primary");
        verify(listener).forModel("xiaomi", "fallback");
        verify(listener, never()).forModel("xiaomi", "ignored");
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
                secretService
        );
    }

    private AiModelMonitorListener monitorListener() {
        AiModelMonitorListener listener = mock(AiModelMonitorListener.class);
        when(listener.forModel(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(listener);
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
