package com.rush.rushaicodemother.service.aimodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.testsupport.AiModelSecretTestFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelViewAssemblerTest {

    private static final String SECRET_API_KEY = "super-secret-key";
    private final AiModelProtectedSecret protectedSecret =
            AiModelSecretTestFixtures.protect(SECRET_API_KEY);
    private final AiModelViewAssembler assembler = new AiModelViewAssembler();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publicViewMustNotExposeRuntimeSecrets() throws Exception {
        String json = objectMapper.writeValueAsString(assembler.toPublicView(configuration()));

        assertFalse(json.contains("apiKey"));
        assertFalse(json.contains(SECRET_API_KEY));
        assertFalse(json.contains(protectedSecret.reference()));
        assertFalse(json.contains(protectedSecret.fingerprint()));
        assertFalse(json.contains("baseUrl"));
        assertFalse(json.contains("configJson"));
    }

    @Test
    void adminViewMustOnlyExposeWhetherApiKeyIsConfigured() throws Exception {
        String json = objectMapper.writeValueAsString(assembler.toAdminView(configuration()));

        assertTrue(json.contains("\"apiKeyConfigured\":true"));
        assertFalse(json.contains("\"apiKey\":"));
        assertFalse(json.contains(SECRET_API_KEY));
        assertFalse(json.contains(protectedSecret.reference()));
        assertFalse(json.contains(protectedSecret.fingerprint()));
    }

    private AiModelConfiguration configuration() {
        return AiModelConfiguration.builder()
                .id(1L)
                .modelName("Test Model")
                .provider("custom")
                .modelId("test-model")
                .baseUrl("https://models.example.com/v1")
                .secretRef(protectedSecret.reference())
                .secretFingerprint(protectedSecret.fingerprint())
                .secretKeyId(protectedSecret.keyId())
                .configJson("{\"protocol\":\"openai_chat_completions\"}")
                .build();
    }
}
