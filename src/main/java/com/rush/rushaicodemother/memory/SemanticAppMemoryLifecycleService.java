package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.GenerationMemoryOutboxProperties;
import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import com.rush.rushaicodemother.service.lifecycle.AppMemoryLifecycleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** 从应用程序删除到派生语义记忆索引的事务桥梁。 */
@Service
public class SemanticAppMemoryLifecycleService implements AppMemoryLifecycleService {

    private final SemanticMemoryDeletionOutboxRepository deletionOutboxRepository;
    private final LongTermMemoryStore memoryStore;
    private final MilvusMemoryProperties memoryProperties;
    private final GenerationMemoryOutboxProperties outboxProperties;
    private final Clock clock;

    @Autowired
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

    /**
 * 处理调度应用记忆删除。
 *
 * @param tenantId 租户编号
 * @param appId 应用编号
 * @param requestedByUserId 目标资源编号
 */
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
