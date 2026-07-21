package com.rush.rushaicodemother.ai.model.failover;

import java.util.Objects;

/** One concrete provider/model client in a priority-ordered runtime pool. */
public record AiModelCandidate<T>(String provider, String modelId, T model) {

    public AiModelCandidate {
        provider = normalize(provider);
        modelId = normalize(modelId);
        Objects.requireNonNull(model, "model");
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
