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
                any(), any(), any(), any(), any(), any()
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
                .rememberNow(any(), any(), any(), any(), any(), any());
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
                taskId, 1L, 2L, GenerationTaskStatus.SUCCESS, "build passed", 1
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
        public List<GenerationMemoryOutboxItem> claimBatch(Instant now, int batchSize, int maxAttempts) {
            return items;
        }

        @Override
        public void markIndexed(String taskId, Instant indexedAt) {
            indexedTaskIds.add(taskId);
        }

        @Override
        public void markFailed(String taskId, String error, Instant failedAt) {
            failedTaskIds.add(taskId);
        }
    }
}
