package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryLongTermMemoryStoreTest {

    @Test
    void searchMustEnforceApplicationUserTypeAndSimilarityBoundaries() {
        InMemoryLongTermMemoryStore store = new InMemoryLongTermMemoryStore(new MilvusMemoryProperties());
        store.upsert(memory("a", 1L, 2L, MemoryType.TASK_OUTCOME, new float[]{1, 0}));
        store.upsert(memory("b", 1L, 9L, MemoryType.TASK_OUTCOME, new float[]{1, 0}));
        store.upsert(memory("c", 1L, 2L, MemoryType.USER_PREFERENCE, new float[]{0, 1}));

        List<SemanticMemoryHit> hits = store.search(new SemanticMemoryQuery(
                1L, 2L, new float[]{1, 0}, Set.of(MemoryType.TASK_OUTCOME), 5, 0.8));

        assertEquals(List.of("a"), hits.stream().map(hit -> hit.memory().id()).toList());
    }

    private SemanticMemory memory(String id, Long appId, Long userId, MemoryType type, float[] embedding) {
        return new SemanticMemory(id, appId, userId, "task", type, id, Map.of(), embedding, Instant.now());
    }
}
