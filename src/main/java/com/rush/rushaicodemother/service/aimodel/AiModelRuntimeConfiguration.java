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
        String secretRef,
        String secretFingerprint,
        String secretKeyId,
        int maxTokens,
        double temperature,
        boolean supportsThinking
) {

    @Override
    public String toString() {
        return "AiModelRuntimeConfiguration[provider=" + provider
                + ", modelId=" + modelId
                + ", modelType=" + modelType
                + ", baseUrl=" + baseUrl
                + ", secretRef=<redacted>, secretFingerprint=<redacted>, secretKeyId=" + secretKeyId
                + ", maxTokens=" + maxTokens
                + ", temperature=" + temperature
                + ", supportsThinking=" + supportsThinking + ']';
    }
}
