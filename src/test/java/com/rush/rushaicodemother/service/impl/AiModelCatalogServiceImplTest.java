package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.model.entity.AiModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelCatalogServiceImplTest {

    private final AiModelCatalogServiceImpl catalogService = new AiModelCatalogServiceImpl();

    @Test
    void shouldExposeXiaomiMimoModelsInCatalog() {
        assertTrue(catalogService.listSupportedModels().stream()
                .anyMatch(model -> "xiaomi".equals(model.getProvider())
                        && "mimo-v2.5-pro".equals(model.getModelId())
                        && Integer.valueOf(1).equals(model.getSupportsThinking())));
    }

    @Test
    void shouldNormalizeXiaomiMimoOfficialModel() {
        AiModel model = AiModel.builder()
                .modelName("MiMo")
                .provider("XIAOMI")
                .modelId("MiMo-V2.5-Pro")
                .baseUrl("https://gateway.example.com/openai/v1")
                .apiKey("test-key")
                .modelType("")
                .temperature(null)
                .maxTokens(null)
                .build();

        AiModel normalized = catalogService.normalizeAndValidate(model);

        assertEquals("xiaomi", normalized.getProvider());
        assertEquals("mimo-v2.5-pro", normalized.getModelId());
        assertEquals("https://gateway.example.com/openai/v1", normalized.getBaseUrl());
        assertEquals("reasoning", normalized.getModelType());
        assertEquals(1.0, normalized.getTemperature());
        assertEquals(32768, normalized.getMaxTokens());
        assertEquals("{\"protocol\":\"openai_chat_completions\"}", normalized.getConfigJson());
    }

    @Test
    void shouldPreserveXiaomiCustomBaseUrl() {
        AiModel model = AiModel.builder()
                .modelName("MiMo Token Plan")
                .provider("xiaomi")
                .modelId("mimo-v2-flash")
                .baseUrl("https://token-plan-sgp.xiaomimimo.com/")
                .apiKey("test-key")
                .modelType("chat")
                .temperature(0.3)
                .maxTokens(4096)
                .build();

        AiModel normalized = catalogService.normalizeAndValidate(model);

        assertEquals("https://token-plan-sgp.xiaomimimo.com/v1", normalized.getBaseUrl());
        assertTrue(catalogService.isRunnable(normalized));
    }

    @Test
    void shouldStripChatCompletionsPathFromOpenAiCompatibleBaseUrl() {
        AiModel model = AiModel.builder()
                .modelName("MiMo Token Plan")
                .provider("xiaomi")
                .modelId("mimo-v2-flash")
                .baseUrl("https://token-plan-cn.xiaomimimo.com/v1/chat/completions")
                .apiKey("test-key")
                .modelType("chat")
                .build();

        AiModel normalized = catalogService.normalizeAndValidate(model);

        assertEquals("https://token-plan-cn.xiaomimimo.com/v1", normalized.getBaseUrl());
    }

    @Test
    void shouldAllowCustomOpenAiChatCompletionsModel() {
        AiModel model = AiModel.builder()
                .modelName("Custom Gateway Model")
                .provider("custom")
                .modelId("custom-chat-model")
                .baseUrl("https://models.example.com/v1/")
                .apiKey("test-key")
                .modelType("chat")
                .configJson("{\"protocol\":\"OpenAI-Chat-Completions\"}")
                .build();

        AiModel normalized = catalogService.normalizeAndValidate(model);

        assertEquals("https://models.example.com/v1", normalized.getBaseUrl());
        assertEquals(8192, normalized.getMaxTokens());
        assertEquals(0.7, normalized.getTemperature());
        assertEquals("{\"protocol\":\"openai_chat_completions\"}", normalized.getConfigJson());
        assertTrue(catalogService.isRunnable(normalized));
    }
}
