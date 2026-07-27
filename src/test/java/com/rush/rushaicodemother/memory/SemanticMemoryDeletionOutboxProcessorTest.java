package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.GenerationMemoryOutboxProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SemanticMemoryDeletionOutboxProcessorTest {

    private static final Instant NOW = Instant.parse("2026-07-21T05:00:00Z");

    @Test
    void successfulDeleteMustCompleteTheClaimedOperation() {
        CapturingRepository repository = new CapturingRepository(List.of(item(1)));
        LongTermMemoryStore memoryStore = mock(LongTermMemoryStore.class);
        SemanticMemoryDeletionOutboxProcessor processor = processor(repository, memoryStore);

        assertEquals(1, processor.processBatch());

        verify(memoryStore).deleteByApplication(3L, 11L);
        assertEquals("operation-1", repository.completedOperationId);
        assertEquals("worker-a", repository.completedLeaseOwner);
    }

    @Test
    void failedDeleteMustReleaseTheLeaseAndScheduleExponentialRetry() {
        CapturingRepository repository = new CapturingRepository(List.of(item(3)));
        LongTermMemoryStore memoryStore = mock(LongTermMemoryStore.class);
        doThrow(new IllegalStateException("milvus unavailable"))
                .when(memoryStore).deleteByApplication(3L, 11L);
        SemanticMemoryDeletionOutboxProcessor processor = processor(repository, memoryStore);

        assertEquals(0, processor.processBatch());

        assertEquals("operation-1", repository.failedOperationId);
        assertEquals(NOW.plusSeconds(120), repository.nextAttemptAt);
        assertEquals("worker-a", repository.failedLeaseOwner);
    }

    private SemanticMemoryDeletionOutboxProcessor processor(
            SemanticMemoryDeletionOutboxRepository repository,
            LongTermMemoryStore memoryStore
    ) {
        return new SemanticMemoryDeletionOutboxProcessor(
                repository,
                memoryStore,
                new GenerationMemoryOutboxProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "worker-a"
        );
    }

    private SemanticMemoryDeletionOutboxItem item(int attempts) {
        return new SemanticMemoryDeletionOutboxItem(
                "operation-1", 3L, 11L, 7L, attempts);
    }

    private static final class CapturingRepository
            implements SemanticMemoryDeletionOutboxRepository {
        private final List<SemanticMemoryDeletionOutboxItem> items;
        private String completedOperationId;
        private String completedLeaseOwner;
        private String failedOperationId;
        private String failedLeaseOwner;
        private Instant nextAttemptAt;

        private CapturingRepository(List<SemanticMemoryDeletionOutboxItem> items) {
            this.items = items;
        }

        @Override
        public void enqueueApplicationDeletion(Long tenantId, Long appId,
                                               Long requestedByUserId, Instant createdAt) {
        }

        @Override
        public List<SemanticMemoryDeletionOutboxItem> claimBatch(
                Instant now, Instant leaseUntil, String leaseOwner, int batchSize) {
            return items;
        }

        @Override
        public boolean markCompleted(String operationId, String leaseOwner, Instant completedAt) {
            completedOperationId = operationId;
            completedLeaseOwner = leaseOwner;
            return true;
        }

        @Override
        public boolean markFailed(String operationId, String leaseOwner, String error,
                                  Instant failedAt, Instant nextAttemptAt) {
            failedOperationId = operationId;
            failedLeaseOwner = leaseOwner;
            this.nextAttemptAt = nextAttemptAt;
            return true;
        }

        @Override
        public SemanticMemoryOutboxBacklog inspectBacklog(Instant now) {
            return SemanticMemoryOutboxBacklog.empty();
        }
    }
}
