package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.service.aimodel.AiModelProtectedSecret;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeConfiguration;
import com.rush.rushaicodemother.testsupport.AiModelSecretTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiThinkingPolicyTest {

    private final OpenAiThinkingPolicy policy = new OpenAiThinkingPolicy();

    @Test
    void unsupportedModelDoesNotEnableThinkingOrProviderParameters() {
        OpenAiThinkingPolicy.ThinkingConfiguration configuration =
                policy.resolve(model("deepseek", "deepseek-chat", false), true);

        assertFalse(configuration.returnThinking());
        assertFalse(configuration.sendThinking());
        assertTrue(configuration.customParameters().isEmpty());
    }

    @Test
    void xiaomiModelReceivesEnabledAndDisabledThinkingParameters() {
        AiModelRuntimeConfiguration model = model("xiaomi", "mimo-v2-flash", true);

        OpenAiThinkingPolicy.ThinkingConfiguration enabled = policy.resolve(model, true);
        OpenAiThinkingPolicy.ThinkingConfiguration disabled = policy.resolve(model, false);

        assertTrue(enabled.returnThinking());
        assertTrue(enabled.sendThinking());
        assertEquals(Map.of("thinking", Map.of("type", "enabled")), enabled.customParameters());
        assertFalse(disabled.returnThinking());
        assertFalse(disabled.sendThinking());
        assertEquals(Map.of("thinking", Map.of("type", "disabled")), disabled.customParameters());
    }

    @Test
    void nonXiaomiReasoningModelUsesOnlyOfficialThinkingFlags() {
        OpenAiThinkingPolicy.ThinkingConfiguration configuration =
                policy.resolve(model("openai", "o3", true), true);

        assertTrue(configuration.returnThinking());
        assertTrue(configuration.sendThinking());
        assertTrue(configuration.customParameters().isEmpty());
    }

    private AiModelRuntimeConfiguration model(String provider, String modelId, boolean supportsThinking) {
        AiModelProtectedSecret secret = AiModelSecretTestFixtures.protect("test-key");
        return new AiModelRuntimeConfiguration(
                provider, modelId, "chat", "https://models.example.com/v1",
                secret.reference(), secret.fingerprint(), secret.keyId(),
                4096, 0.7, supportsThinking
        );
    }
}
