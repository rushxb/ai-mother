package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.testsupport.AiModelSecretTestFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelConfigurationPolicyTest {

    private final AiModelSecretService secretService = AiModelSecretTestFixtures.service();
    private final AiModelConfigurationPolicy policy = new AiModelConfigurationPolicy(secretService);

    @Test
    void catalogMustExposeSupportedXiaomiReasoningModel() {
        assertTrue(policy.listSupportedModels().stream()
                .anyMatch(model -> "xiaomi".equals(model.getProvider())
                        && "mimo-v2.5-pro".equals(model.getModelId())
                        && Integer.valueOf(1).equals(model.getSupportsThinking())));
    }

    @Test
    void supportedModelMustReceiveCatalogDefaults() {
        AiModelConfiguration normalized = policy.normalizeAndValidate(configuration(
                "XIAOMI", "MiMo-V2.5-Pro", "https://gateway.example.com/openai/v1", ""
        ));

        assertEquals("xiaomi", normalized.getProvider());
        assertEquals("mimo-v2.5-pro", normalized.getModelId());
        assertEquals("reasoning", normalized.getModelType());
        assertEquals(1.0, normalized.getTemperature());
        assertEquals(32768, normalized.getMaxTokens());
        assertEquals("{\"protocol\":\"openai_chat_completions\"}", normalized.getConfigJson());
    }

    @Test
    void localhostHttpBaseUrlMustRemainSupportedAndNormalizeToV1() {
        AiModelConfiguration normalized = policy.normalizeAndValidate(configuration(
                "custom", "local-model", "http://localhost:11434", "chat"
        ));

        assertEquals("http://localhost:11434/v1", normalized.getBaseUrl());
        assertTrue(policy.isRunnable(normalized));
    }

    @Test
    void chatCompletionsPathMustNormalizeToOpenAiBaseUrl() {
        AiModelConfiguration normalized = policy.normalizeAndValidate(configuration(
                "custom", "model", "https://models.example.com/v1/chat/completions", "chat"
        ));

        assertEquals("https://models.example.com/v1", normalized.getBaseUrl());
    }

    @Test
    void nonHttpProtocolAndUrlSecretsMustBeRejected() {
        assertThrows(BusinessException.class, () -> policy.normalizeAndValidate(configuration(
                "custom", "model", "file:///tmp/model", "chat"
        )));
        assertThrows(BusinessException.class, () -> policy.normalizeAndValidate(configuration(
                "custom", "model", "https://user:secret@models.example.com/v1", "chat"
        )));
    }

    private AiModelConfiguration configuration(String provider,
                                               String modelId,
                                               String baseUrl,
                                               String modelType) {
        AiModelProtectedSecret secret = AiModelSecretTestFixtures.protect("test-key");
        return AiModelConfiguration.builder()
                .modelName("Model")
                .provider(provider)
                .modelId(modelId)
                .baseUrl(baseUrl)
                .secretRef(secret.reference())
                .secretFingerprint(secret.fingerprint())
                .secretKeyId(secret.keyId())
                .modelType(modelType)
                .isEnabled(1)
                .supportsThinking(0)
                .sortOrder(0)
                .build();
    }
}
