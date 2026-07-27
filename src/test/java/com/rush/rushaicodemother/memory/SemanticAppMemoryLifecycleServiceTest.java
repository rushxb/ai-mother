package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.GenerationMemoryOutboxProperties;
import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SemanticAppMemoryLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-21T06:00:00Z");

    @Test
    void enabledMilvusMustEnqueueDeletionInsideTheRelationalTransactionBoundary() {
        SemanticMemoryDeletionOutboxRepository repository =
                mock(SemanticMemoryDeletionOutboxRepository.class);
        LongTermMemoryStore store = mock(LongTermMemoryStore.class);
        MilvusMemoryProperties memoryProperties = new MilvusMemoryProperties();
        memoryProperties.setEnabled(true);
        GenerationMemoryOutboxProperties outboxProperties = new GenerationMemoryOutboxProperties();
        SemanticAppMemoryLifecycleService service = new SemanticAppMemoryLifecycleService(
                repository, store, memoryProperties, outboxProperties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.scheduleApplicationMemoryDeletion(3L, 11L, 7L);

        verify(repository).enqueueApplicationDeletion(3L, 11L, 7L, NOW);
        verify(store, never()).deleteByApplication(3L, 11L);
    }

    @Test
    void disabledMilvusMustDeleteOnlyTheProcessLocalFallback() {
        SemanticMemoryDeletionOutboxRepository repository =
                mock(SemanticMemoryDeletionOutboxRepository.class);
        LongTermMemoryStore store = mock(LongTermMemoryStore.class);
        MilvusMemoryProperties memoryProperties = new MilvusMemoryProperties();
        GenerationMemoryOutboxProperties outboxProperties = new GenerationMemoryOutboxProperties();
        SemanticAppMemoryLifecycleService service = new SemanticAppMemoryLifecycleService(
                repository, store, memoryProperties, outboxProperties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.scheduleApplicationMemoryDeletion(3L, 11L, 7L);

        verify(store).deleteByApplication(3L, 11L);
        verify(repository, never()).enqueueApplicationDeletion(3L, 11L, 7L, NOW);
    }
}
