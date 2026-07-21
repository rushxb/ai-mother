package com.rush.rushaicodemother.model.event;

public record AiModelCircuitOpenedEvent(String provider, String modelId) {

    public AiModelCircuitOpenedEvent(String modelId) {
        this("unknown", modelId);
    }
}
