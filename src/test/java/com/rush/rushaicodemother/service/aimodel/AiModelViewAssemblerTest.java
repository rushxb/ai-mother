package com.rush.rushaicodemother.service.aimodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelViewAssemblerTest {

    private static final String SECRET_API_KEY = "super-secret-key";
    private final AiModelViewAssembler assembler = new AiModelViewAssembler();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publicViewMustNotExposeRuntimeSecrets() throws Exception {
        String json = objectMapper.writeValueAsString(assembler.toPublicView(configuration()));

        assertFalse(json.contains("apiKey"));
        assertFalse(json.contains(SECRET_API_KEY));
        assertFalse(json.contains("baseUrl"));
        assertFalse(json.contains("configJson"));
    }

    @Test
    void adminViewMustOnlyExposeWhetherApiKeyIsConfigured() throws Exception {
        String json = objectMapper.writeValueAsString(assembler.toAdminView(configuration()));

        assertTrue(json.contains("\"apiKeyConfigured\":true"));
        assertFalse(json.contains("\"apiKey\":"));
        assertFalse(json.contains(SECRET_API_KEY));
    }

    private AiModelConfiguration configuration() {
        return AiModelConfiguration.builder()
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
