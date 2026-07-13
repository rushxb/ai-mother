package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.service.AiModelCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class AiModelServiceImplSecurityTest {

    private AiModelServiceImpl service;

    @BeforeEach
    void setUp() {
        AiModelCatalogService catalogService = mock(AiModelCatalogService.class);
        when(catalogService.normalizeAndValidate(any(AiModel.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = spy(new AiModelServiceImpl(catalogService, mock(StringRedisTemplate.class)));
    }

    @Test
    void blankApiKeyMustKeepExistingSecretDuringUpdate() {
        AiModel existingModel = existingModel();
        AiModel update = AiModel.builder()
                .id(existingModel.getId())
                .modelName("Updated Model")
                .apiKey("   ")
                .build();
        stubPersistence(existingModel);

        boolean updated = service.updateModel(update);

        assertTrue(updated);
        assertEquals("existing-secret", existingModel.getApiKey());
        assertEquals("Updated Model", existingModel.getModelName());
    }

    @Test
    void nonBlankApiKeyMustReplaceExistingSecretDuringUpdate() {
        AiModel existingModel = existingModel();
        AiModel update = AiModel.builder()
                .id(existingModel.getId())
                .apiKey("replacement-secret")
                .build();
        stubPersistence(existingModel);

        boolean updated = service.updateModel(update);

        assertTrue(updated);
        assertEquals("replacement-secret", existingModel.getApiKey());
    }

    private void stubPersistence(AiModel existingModel) {
        doReturn(existingModel).when(service).getById(existingModel.getId());
        doReturn(true).when(service).updateById(any(AiModel.class));
    }

    private AiModel existingModel() {
        return AiModel.builder()
                .id(7L)
                .modelName("Existing Model")
                .provider("custom")
                .modelId("existing-model")
                .baseUrl("https://models.example.com/v1")
                .apiKey("existing-secret")
                .isEnabled(0)
                .build();
    }
}
