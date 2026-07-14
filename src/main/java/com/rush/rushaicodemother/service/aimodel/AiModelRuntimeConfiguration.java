package com.rush.rushaicodemother.service.aimodel;

/**
 * 创建 LangChain4j 模型所需的最小运行时配置。
 *
 * <p>该对象包含 API Key，只能注入运行时模型工厂，不能暴露给 Web 层。</p>
 */
public record AiModelRuntimeConfiguration(
        String provider,
        String modelId,
        String modelType,
        String baseUrl,
        String apiKey,
        int maxTokens,
        double temperature,
        boolean supportsThinking
) {
}
