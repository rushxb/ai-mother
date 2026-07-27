package com.rush.rushaicodemother.memory;

import java.util.Set;

/**
 * 语义记忆查询的不可变数据载体。
 */
public record SemanticMemoryQuery(
        Long tenantId,
        Long appId,
        float[] embedding,
        Set<MemoryType> types,
        int topK,
        double minimumScore
) {
    public SemanticMemoryQuery {
        embedding = embedding == null ? new float[0] : embedding.clone();
        types = types == null ? Set.of() : Set.copyOf(types);
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }
}
