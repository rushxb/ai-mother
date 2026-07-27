package com.rush.rushaicodemother.ai.model.failover;

import java.util.Objects;

/** 按优先级排序的运行时池中的一个具体提供者/模型客户端。 */
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
