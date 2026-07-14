package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.monitor.AiModelMonitorListener;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeConfiguration;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamingModelFactoryTest {

    @Test
    void fastTasksMustReadOnlyChatRuntimeConfiguration() {
        AiModelRuntimeService runtimeService = mock(AiModelRuntimeService.class);
        when(runtimeService.requireRunnableModelByType("chat")).thenReturn(model("chat"));
        StreamingModelFactory factory = factory(runtimeService);

        factory.createRoutingChatModel();
        factory.createCreateSpecChatModel();
        factory.createChatModel();
        factory.createModel(GenerationPerformanceProfile.speedFirst());

        verify(runtimeService, times(4)).requireRunnableModelByType("chat");
    }

    @Test
    void qualityTasksMustReadOnlyReasoningRuntimeConfiguration() {
        AiModelRuntimeService runtimeService = mock(AiModelRuntimeService.class);
        when(runtimeService.requireRunnableModelByType("reasoning")).thenReturn(model("reasoning"));
        StreamingModelFactory factory = factory(runtimeService);

        factory.createPrimaryChatModel();
        factory.createReasoningModel();
        factory.createModel(GenerationPerformanceProfile.qualityFirst());

        verify(runtimeService, times(3)).requireRunnableModelByType("reasoning");
    }

    @Test
    void missingRuntimeConfigurationMustFailFast() {
        AiModelRuntimeService runtimeService = mock(AiModelRuntimeService.class);
        when(runtimeService.requireRunnableModelByType("chat"))
                .thenThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "missing"));

        assertThrows(BusinessException.class, () -> factory(runtimeService).createCreateSpecChatModel());
    }

    private StreamingModelFactory factory(AiModelRuntimeService runtimeService) {
        return new StreamingModelFactory(
                mock(AiModelMonitorListener.class),
                runtimeService,
                new AiModelRuntimeProperties(),
                new OpenAiThinkingPolicy()
        );
    }

    private AiModelRuntimeConfiguration model(String modelType) {
        boolean reasoning = "reasoning".equals(modelType);
        return new AiModelRuntimeConfiguration(
                "xiaomi",
                reasoning ? "mimo-v2.5-pro" : "mimo-v2-flash",
                modelType,
                "https://models.example.com/v1",
                "test-key",
                4096,
                reasoning ? 1.0 : 0.3,
                reasoning
        );
    }
}
