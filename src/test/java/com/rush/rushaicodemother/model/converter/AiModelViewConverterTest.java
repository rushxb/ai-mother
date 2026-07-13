package com.rush.rushaicodemother.model.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.model.entity.AiModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelViewConverterTest {

    private static final String SECRET_API_KEY = "super-secret-key";

    private final AiModelViewConverter converter = new AiModelViewConverter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publicViewMustNotExposeRuntimeSecrets() throws Exception {
        AiModel model = createSensitiveModel();

        String json = objectMapper.writeValueAsString(converter.toPublicVO(model));

        assertFalse(json.contains("apiKey"));
        assertFalse(json.contains(SECRET_API_KEY));
        assertFalse(json.contains("baseUrl"));
        assertFalse(json.contains("configJson"));
    }

    @Test
    void adminViewMustOnlyExposeWhetherApiKeyIsConfigured() throws Exception {
        AiModel model = createSensitiveModel();

        String json = objectMapper.writeValueAsString(converter.toAdminVO(model));

        assertTrue(json.contains("\"apiKeyConfigured\":true"));
        assertFalse(json.contains("\"apiKey\":"));
        assertFalse(json.contains(SECRET_API_KEY));
    }

    private AiModel createSensitiveModel() {
        return AiModel.builder()
                .id(1L)
                .modelName("Test Model")
                .provider("custom")
                .modelId("test-model")
                .baseUrl("https://models.example.com/v1")
                .apiKey(SECRET_API_KEY)
                .configJson("{\"protocol\":\"openai_chat_completions\"}")
                .build();
    }
}
