package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeConfiguration;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 解决LangChain4j官方思维选项和提供商特定的请求参数。
 */
@Component
public class OpenAiThinkingPolicy {

    public ThinkingConfiguration resolve(AiModelRuntimeConfiguration model, boolean thinkingRequested) {
        boolean thinkingSupported = model != null && model.supportsThinking();
        boolean thinkingEnabled = thinkingSupported && thinkingRequested;
        Map<String, Object> customParameters = thinkingSupported && isXiaomiMimoModel(model)
                ? Map.of("thinking", Map.of("type", thinkingEnabled ? "enabled" : "disabled"))
                : Map.of();
        return new ThinkingConfiguration(thinkingEnabled, thinkingEnabled, customParameters);
    }

    private boolean isXiaomiMimoModel(AiModelRuntimeConfiguration model) {
        String provider = model.provider() == null ? "" : model.provider().toLowerCase(Locale.ROOT);
        String modelId = model.modelId() == null ? "" : model.modelId().toLowerCase(Locale.ROOT);
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
