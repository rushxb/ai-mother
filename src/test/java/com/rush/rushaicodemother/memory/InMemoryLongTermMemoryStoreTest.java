package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import cn.hutool.crypto.digest.DigestUtil;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryLongTermMemoryStoreTest {

    @Test
    void searchMustEnforceTenantApplicationTypeAndSimilarityBoundaries() {
        InMemoryLongTermMemoryStore store = new InMemoryLongTermMemoryStore(new MilvusMemoryProperties());
        store.upsert(memory("a", 1L, 10L, 2L, MemoryType.TASK_OUTCOME, new float[]{1, 0}));
        store.upsert(memory("b", 2L, 10L, 2L, MemoryType.TASK_OUTCOME, new float[]{1, 0}));
        store.upsert(memory("c", 1L, 10L, 2L, MemoryType.USER_PREFERENCE, new float[]{0, 1}));
        store.upsert(memory("d", 1L, 10L, 9L, MemoryType.TASK_OUTCOME, new float[]{1, 0}));

        List<SemanticMemoryHit> hits = store.search(new SemanticMemoryQuery(
                1L, 10L, new float[]{1, 0}, Set.of(MemoryType.TASK_OUTCOME), 5, 0.8));

        assertEquals(List.of("a", "d"),
                hits.stream().map(hit -> hit.memory().content()).sorted().toList());
    }

    private SemanticMemory memory(String id, Long tenantId, Long appId, Long userId,
                                  MemoryType type, float[] embedding) {
        return new SemanticMemory(
                DigestUtil.sha256Hex(id), tenantId, appId, userId, "task", type, id,
                SemanticMemoryGovernancePolicy.governMetadata(Map.of(), id),
                embedding, Instant.now());
    }
}
