package com.rush.rushaicodemother.memory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Bounded local semantic-memory fallback used when Milvus is unavailable. */
@Component
public class InMemoryLongTermMemoryStore implements LongTermMemoryStore {
    private final Cache<String, SemanticMemory> memories;

    public InMemoryLongTermMemoryStore(MilvusMemoryProperties properties) {
        this.memories = Caffeine.newBuilder()
                .maximumSize(properties.getFallbackMaxEntries())
                .expireAfterWrite(properties.getFallbackRetention())
                .build();
    }

    @Override
    public void upsert(SemanticMemory memory) {
        if (memory != null && memory.id() != null && !memory.id().isBlank()) {
            memories.put(memory.id(), memory);
        }
    }

    @Override
    public List<SemanticMemoryHit> search(SemanticMemoryQuery query) {
        if (query == null || query.embedding().length == 0 || query.topK() <= 0) {
            return List.of();
        }
        return memories.asMap().values().stream()
                .filter(memory -> Objects.equals(memory.tenantId(), query.tenantId()))
                .filter(memory -> Objects.equals(memory.appId(), query.appId()))
                .filter(memory -> query.types().isEmpty() || query.types().contains(memory.type()))
                .map(memory -> new SemanticMemoryHit(memory, cosine(query.embedding(), memory.embedding())))
                .filter(hit -> hit.score() >= query.minimumScore())
                .sorted(Comparator.comparingDouble(SemanticMemoryHit::score).reversed()
                        .thenComparing(hit -> hit.memory().createdAt(), Comparator.reverseOrder()))
                .limit(query.topK())
                .toList();
    }

    @Override
    public void deleteByApplication(Long tenantId, Long appId) {
        if (tenantId == null || tenantId <= 0 || appId == null || appId <= 0) {
            throw new IllegalArgumentException("tenantId and appId must be positive");
        }
        memories.asMap().entrySet().removeIf(entry ->
                Objects.equals(entry.getValue().tenantId(), tenantId)
                        && Objects.equals(entry.getValue().appId(), appId)
        );
    }

    private double cosine(float[] left, float[] right) {
        if (left.length == 0 || left.length != right.length) {
            return -1.0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        return leftNorm == 0 || rightNorm == 0 ? -1.0 : dot / Math.sqrt(leftNorm * rightNorm);
    }
}
