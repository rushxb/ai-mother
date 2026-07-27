package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationSemanticMemoryServiceTest {

    @Test
    void repeatedOutcomeMustUpsertTheSameGovernedMemoryIdentity() {
        CapturingStore store = new CapturingStore();
        MemoryEmbeddingService embeddingService = new MemoryEmbeddingService() {
            @Override
            public float[] embed(String text) {
                return new float[]{1.0f, 0.0f};
            }

            @Override
            public int dimension() {
                return 2;
            }

            @Override
            public String modelId() {
                return "test-embedding";
            }

            @Override
            public String modelVersion() {
                return "v1";
            }
        };
        TaskExecutor directExecutor = Runnable::run;
        GenerationSemanticMemoryService service = new GenerationSemanticMemoryService(
                store, embeddingService, new MilvusMemoryProperties(), directExecutor
        );

        service.rememberAsync(9L, 1L, 2L, "task-1", MemoryType.TASK_OUTCOME,
                "build passed", Map.of("route", "heavy"));
        service.rememberAsync(9L, 1L, 2L, "task-1", MemoryType.TASK_OUTCOME,
                "build passed", Map.of("route", "heavy"));

        assertEquals(2, store.memories.size());
        assertEquals(store.memories.get(0).id(), store.memories.get(1).id());
        assertEquals("v2", store.memories.get(0).metadata().get("schemaVersion"));
        assertEquals("generation_task", store.memories.get(0).metadata().get("source"));
        assertEquals("untrusted_history", store.memories.get(0).metadata().get("trust"));
    }

    private static final class CapturingStore implements LongTermMemoryStore {
        private final List<SemanticMemory> memories = new ArrayList<>();

        @Override
        public void upsert(SemanticMemory memory) {
            memories.add(memory);
        }

        @Override
        public List<SemanticMemoryHit> search(SemanticMemoryQuery query) {
            return List.of();
        }

        @Override
        public void deleteByApplication(Long tenantId, Long appId) {
            memories.removeIf(memory -> memory.tenantId().equals(tenantId)
                    && memory.appId().equals(appId));
        }
    }
}
