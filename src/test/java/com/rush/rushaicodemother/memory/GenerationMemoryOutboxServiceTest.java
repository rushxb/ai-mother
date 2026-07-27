package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.GenerationMemoryOutboxProperties;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GenerationMemoryOutboxServiceTest {

    @Test
    void successfulIndexMustMarkTheDurableOutboxItemComplete() {
        CapturingRepository repository = new CapturingRepository(List.of(item("task-1")));
        GenerationSemanticMemoryService semanticMemoryService = mock(GenerationSemanticMemoryService.class);
        GenerationMemoryOutboxService service = new GenerationMemoryOutboxService(
                repository,
                semanticMemoryService,
                new GenerationMemoryOutboxProperties(),
                Clock.fixed(Instant.parse("2026-07-16T10:00:00Z"), ZoneOffset.UTC)
        );

        assertEquals(1, service.processBatch());

        verify(semanticMemoryService).rememberNow(
                eq(9L), eq(1L), eq(2L), eq("task-1"), eq(MemoryType.TASK_OUTCOME),
                eq("用户需求：创建订单管理页面\n执行结果：构建通过"),
                eq(java.util.Map.of(
                        "source", "generation_task_outbox",
                        "taskStatus", "success",
                        "orchestrationMode", "graph",
                        "targetType", "vue_project"
                ))
        );
        assertEquals(List.of("task-1"), repository.indexedTaskIds);
        assertEquals(List.of(), repository.failedTaskIds);
    }

    @Test
    void failedMilvusWriteMustRemainPendingForARepairScan() {
        CapturingRepository repository = new CapturingRepository(List.of(item("task-2")));
        GenerationSemanticMemoryService semanticMemoryService = mock(GenerationSemanticMemoryService.class);
        doThrow(new IllegalStateException("milvus unavailable"))
                .when(semanticMemoryService)
                .rememberNow(any(), any(), any(), any(), any(), any(), any());
        GenerationMemoryOutboxService service = new GenerationMemoryOutboxService(
                repository,
                semanticMemoryService,
                new GenerationMemoryOutboxProperties(),
                Clock.fixed(Instant.parse("2026-07-16T10:00:00Z"), ZoneOffset.UTC)
        );

        assertEquals(0, service.processBatch());

        assertEquals(List.of(), repository.indexedTaskIds);
        assertEquals(List.of("task-2"), repository.failedTaskIds);
    }

    private GenerationMemoryOutboxItem item(String taskId) {
        return new GenerationMemoryOutboxItem(
                taskId, 9L, 1L, 2L, GenerationTaskStatus.SUCCESS,
                "创建订单管理页面", "构建通过", "graph", "vue_project", 1
        );
    }

    private static final class CapturingRepository implements GenerationMemoryOutboxRepository {
        private final List<GenerationMemoryOutboxItem> items;
        private final List<String> indexedTaskIds = new ArrayList<>();
        private final List<String> failedTaskIds = new ArrayList<>();

        private CapturingRepository(List<GenerationMemoryOutboxItem> items) {
            this.items = items;
        }

        @Override
        public List<GenerationMemoryOutboxItem> claimBatch(Instant now,
                                                           Instant leaseUntil,
                                                           String leaseOwner,
                                                           int batchSize,
                                                           int maxAttempts) {
            return items;
        }

        @Override
        public boolean markIndexed(String taskId, String leaseOwner, Instant indexedAt) {
            indexedTaskIds.add(taskId);
            return true;
        }

        @Override
        public boolean markFailed(String taskId,
                                  String leaseOwner,
                                  String error,
                                  Instant failedAt,
                                  Instant nextAttemptAt) {
            failedTaskIds.add(taskId);
            return true;
        }

        @Override
        public SemanticMemoryOutboxBacklog inspectBacklog(Instant now, int maxAttempts) {
            return SemanticMemoryOutboxBacklog.empty();
        }
    }
}
