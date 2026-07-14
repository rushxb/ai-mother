package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAiModelRuntimeServiceTest {

    private final AiModelPersistenceService persistenceService = mock(AiModelPersistenceService.class);
    private final DefaultAiModelRuntimeService service = new DefaultAiModelRuntimeService(
            persistenceService,
            new AiModelConfigurationPolicy()
    );

    @Test
    void mustSkipInvalidEnabledRecordsAndReturnMinimalRuntimeConfiguration() {
        when(persistenceService.findEnabled("chat")).thenReturn(List.of(
                configuration("", "bad"),
                configuration("secret", "good")
        ));

        AiModelRuntimeConfiguration result = service.requireRunnableModelByType("chat");

        assertEquals("good", result.modelId());
        assertEquals("secret", result.apiKey());
    }

    @Test
    void generationPreflightMustRequireChatAndReasoningModels() {
        when(persistenceService.findEnabled("chat")).thenReturn(List.of(configuration("secret", "chat-model")));
        when(persistenceService.findEnabled("reasoning"))
                .thenReturn(List.of(configuration("secret", "reasoning-model").toBuilder()
                        .modelType("reasoning")
                        .build()));

        service.ensureGenerationModelsConfigured();

        verify(persistenceService).findEnabled("chat");
        verify(persistenceService).findEnabled("reasoning");
    }

    @Test
    void missingRunnableModelMustFailWithBusinessException() {
        when(persistenceService.findEnabled("chat")).thenReturn(List.of());

        assertThrows(BusinessException.class, () -> service.requireRunnableModelByType("chat"));
    }

    private AiModelConfiguration configuration(String apiKey, String modelId) {
        return AiModelConfiguration.builder()
                .id(7L)
                .modelName("Model")
                .provider("custom")
                .modelId(modelId)
                .baseUrl("http://localhost:11434/v1")
                .apiKey(apiKey)
                .maxTokens(4096)
                .temperature(0.7)
                .isEnabled(1)
                .modelType("chat")
                .supportsThinking(0)
                .sortOrder(0)
                .build();
    }
}
