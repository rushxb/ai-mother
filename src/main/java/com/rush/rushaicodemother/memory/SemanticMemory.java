package com.rush.rushaicodemother.memory;

import java.time.Instant;
import java.util.Map;

public record SemanticMemory(
        String id,
        Long tenantId,
        Long appId,
        Long userId,
        String taskId,
        MemoryType type,
        String content,
        Map<String, Object> metadata,
        float[] embedding,
        Instant createdAt
) {
    public SemanticMemory {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        embedding = embedding == null ? new float[0] : embedding.clone();
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }
}
