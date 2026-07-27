package com.rush.rushaicodemother.model.event;

/**
 * AI 模型熔断Opened领域事件。
 */
public record AiModelCircuitOpenedEvent(String provider, String modelId) {

    public AiModelCircuitOpenedEvent(String modelId) {
        this("unknown", modelId);
    }
}
