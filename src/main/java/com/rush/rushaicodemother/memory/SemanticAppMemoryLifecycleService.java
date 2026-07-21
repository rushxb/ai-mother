package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.GenerationMemoryOutboxProperties;
import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import com.rush.rushaicodemother.service.lifecycle.AppMemoryLifecycleService;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** Transactional bridge from application deletion to the derived semantic-memory index. */
@Service
public class SemanticAppMemoryLifecycleService implements AppMemoryLifecycleService {

    private final SemanticMemoryDeletionOutboxRepository deletionOutboxRepository;
    private final LongTermMemoryStore memoryStore;
    private final MilvusMemoryProperties memoryProperties;
    private final GenerationMemoryOutboxProperties outboxProperties;
    private final Clock clock;

    public SemanticAppMemoryLifecycleService(
            SemanticMemoryDeletionOutboxRepository deletionOutboxRepository,
            LongTermMemoryStore memoryStore,
            MilvusMemoryProperties memoryProperties,
            GenerationMemoryOutboxProperties outboxProperties
    ) {
        this(deletionOutboxRepository, memoryStore, memoryProperties, outboxProperties, Clock.systemUTC());
    }

    SemanticAppMemoryLifecycleService(
            SemanticMemoryDeletionOutboxRepository deletionOutboxRepository,
            LongTermMemoryStore memoryStore,
            MilvusMemoryProperties memoryProperties,
            GenerationMemoryOutboxProperties outboxProperties,
            Clock clock
    ) {
        this.deletionOutboxRepository = deletionOutboxRepository;
        this.memoryStore = memoryStore;
        this.memoryProperties = memoryProperties;
        this.outboxProperties = outboxProperties;
        this.clock = clock;
    }

    @Override
    public void scheduleApplicationMemoryDeletion(Long tenantId,
                                                  Long appId,
                                                  Long requestedByUserId) {
        requirePositive(tenantId, "tenantId");
        requirePositive(appId, "appId");
        requirePositive(requestedByUserId, "requestedByUserId");
        if (memoryProperties.isEnabled() && outboxProperties.isEnabled()) {
            deletionOutboxRepository.enqueueApplicationDeletion(
                    tenantId, appId, requestedByUserId, clock.instant());
            return;
        }
        memoryStore.deleteByApplication(tenantId, appId);
    }

    private void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
