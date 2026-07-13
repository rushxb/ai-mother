package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.model.entity.AiModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiThinkingPolicyTest {

    private final OpenAiThinkingPolicy policy = new OpenAiThinkingPolicy();

    @Test
    void unsupportedModelDoesNotEnableThinkingOrProviderParameters() {
        AiModel model = model("deepseek", "deepseek-chat", 0);

        OpenAiThinkingPolicy.ThinkingConfiguration configuration = policy.resolve(model, true);

        assertFalse(configuration.returnThinking());
        assertFalse(configuration.sendThinking());
        assertTrue(configuration.customParameters().isEmpty());
    }

    @Test
    void xiaomiModelReceivesEnabledThinkingParameter() {
        AiModel model = model("xiaomi", "mimo-v2-flash", 1);

        OpenAiThinkingPolicy.ThinkingConfiguration configuration = policy.resolve(model, true);

        assertTrue(configuration.returnThinking());
        assertTrue(configuration.sendThinking());
        assertEquals(Map.of("thinking", Map.of("type", "enabled")), configuration.customParameters());
    }

    @Test
    void xiaomiModelReceivesDisabledThinkingParameterWhenNotRequested() {
        AiModel model = model("xiaomi", "mimo-v2-flash", 1);

        OpenAiThinkingPolicy.ThinkingConfiguration configuration = policy.resolve(model, false);

        assertFalse(configuration.returnThinking());
        assertFalse(configuration.sendThinking());
        assertEquals(Map.of("thinking", Map.of("type", "disabled")), configuration.customParameters());
    }

    @Test
    void deepSeekThinkingUsesOfficialFlagsWithoutXiaomiParameter() {
        AiModel model = model("deepseek", "deepseek-reasoner", 1);

        OpenAiThinkingPolicy.ThinkingConfiguration configuration = policy.resolve(model, true);

        assertTrue(configuration.returnThinking());
        assertTrue(configuration.sendThinking());
        assertTrue(configuration.customParameters().isEmpty());
    }

    @Test
    void openAiReasoningModelDoesNotReceiveXiaomiParameter() {
        AiModel model = model("openai", "o3", 1);

        OpenAiThinkingPolicy.ThinkingConfiguration configuration = policy.resolve(model, true);

        assertTrue(configuration.returnThinking());
        assertTrue(configuration.sendThinking());
        assertTrue(configuration.customParameters().isEmpty());
    }

    private AiModel model(String provider, String modelId, int supportsThinking) {
        AiModel model = new AiModel();
        model.setProvider(provider);
        model.setModelId(modelId);
        model.setSupportsThinking(supportsThinking);
        return model;
    }
}
