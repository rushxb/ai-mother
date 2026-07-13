package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.monitor.AiModelMonitorListener;
import com.rush.rushaicodemother.service.AiModelCatalogService;
import com.rush.rushaicodemother.service.AiModelService;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamingModelFactoryTest {

    @Test
    void shouldUseFastChatModelForRoutingCreateSpecAndFastTasks() {
        AiModelService modelService = mock(AiModelService.class);
        AiModelCatalogService catalogService = mock(AiModelCatalogService.class);
        AiModel chatModel = model("chat");
        when(modelService.listRunnableEnabledModelsByType("chat")).thenReturn(List.of(chatModel));
        when(catalogService.normalizeForRuntime(chatModel)).thenReturn(chatModel);
        StreamingModelFactory factory = factory(modelService, catalogService);

        factory.createRoutingChatModel();
        factory.createCreateSpecChatModel();
        factory.createChatModel();
        factory.createModel(GenerationPerformanceProfile.speedFirst());

        verify(modelService, org.mockito.Mockito.times(4)).listRunnableEnabledModelsByType("chat");
        verify(modelService, never()).listRunnableEnabledModels();
    }

    @Test
    void shouldUseReasoningModelForPrimaryAndQualityTasks() {
        AiModelService modelService = mock(AiModelService.class);
        AiModelCatalogService catalogService = mock(AiModelCatalogService.class);
        AiModel reasoningModel = model("reasoning");
        when(modelService.listRunnableEnabledModelsByType("reasoning")).thenReturn(List.of(reasoningModel));
        when(catalogService.normalizeForRuntime(reasoningModel)).thenReturn(reasoningModel);
        StreamingModelFactory factory = factory(modelService, catalogService);

        factory.createPrimaryChatModel();
        factory.createReasoningModel();
        factory.createModel(GenerationPerformanceProfile.qualityFirst());

        verify(modelService, org.mockito.Mockito.times(3)).listRunnableEnabledModelsByType("reasoning");
        verify(modelService, never()).listRunnableEnabledModels();
    }

    @Test
    void shouldFailFastWhenFastModelIsMissing() {
        AiModelService modelService = mock(AiModelService.class);
        AiModelCatalogService catalogService = mock(AiModelCatalogService.class);
        when(modelService.listRunnableEnabledModelsByType("chat")).thenReturn(List.of());
        StreamingModelFactory factory = factory(modelService, catalogService);

        assertThrows(BusinessException.class, factory::createCreateSpecChatModel);

        verify(modelService).listRunnableEnabledModelsByType(eq("chat"));
        verify(modelService, never()).listRunnableEnabledModels();
        verify(catalogService, never()).normalizeForRuntime(any());
    }

    private StreamingModelFactory factory(AiModelService modelService, AiModelCatalogService catalogService) {
        return new StreamingModelFactory(
                mock(AiModelMonitorListener.class),
                modelService,
                catalogService,
                new AiModelRuntimeProperties(),
                new OpenAiThinkingPolicy()
        );
    }

    private AiModel model(String modelType) {
        return AiModel.builder()
                .provider("xiaomi")
                .modelName(modelType + " model")
                .modelId("chat".equals(modelType) ? "mimo-v2-flash" : "mimo-v2.5-pro")
                .baseUrl("https://token-plan-cn.xiaomimimo.com/v1")
                .apiKey("test-key")
                .modelType(modelType)
                .maxTokens(4096)
                .temperature("chat".equals(modelType) ? 0.3 : 1.0)
                .supportsThinking("reasoning".equals(modelType) ? 1 : 0)
                .build();
    }
}
