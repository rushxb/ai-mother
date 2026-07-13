package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.model.entity.AiModel;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves official LangChain4j thinking options and provider-specific request parameters.
 */
@Component
public class OpenAiThinkingPolicy {

    public ThinkingConfiguration resolve(AiModel model, boolean thinkingRequested) {
        boolean thinkingSupported = model != null && Integer.valueOf(1).equals(model.getSupportsThinking());
        boolean thinkingEnabled = thinkingSupported && thinkingRequested;
        Map<String, Object> customParameters = thinkingSupported && isXiaomiMimoModel(model)
                ? Map.of("thinking", Map.of("type", thinkingEnabled ? "enabled" : "disabled"))
                : Map.of();
        return new ThinkingConfiguration(thinkingEnabled, thinkingEnabled, customParameters);
    }

    private boolean isXiaomiMimoModel(AiModel model) {
        String provider = model.getProvider() == null ? "" : model.getProvider().toLowerCase(Locale.ROOT);
        String modelId = model.getModelId() == null ? "" : model.getModelId().toLowerCase(Locale.ROOT);
        return provider.equals("xiaomi") || modelId.startsWith("mimo-v2");
    }

    public record ThinkingConfiguration(boolean returnThinking,
                                        boolean sendThinking,
                                        Map<String, Object> customParameters) {

        public ThinkingConfiguration {
            customParameters = customParameters == null ? Map.of() : Map.copyOf(customParameters);
        }
    }
}
