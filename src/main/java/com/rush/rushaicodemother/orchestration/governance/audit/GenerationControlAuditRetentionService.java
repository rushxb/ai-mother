package com.rush.rushaicodemother.orchestration.governance.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** 按事件自身的 expiresAt 有界删除过期审计数据。 */
@Slf4j
@Service
public class GenerationControlAuditRetentionService {

    private final GenerationControlAuditStore store;
    private final GenerationControlAuditProperties properties;
    private final Clock clock;

    @Autowired
    public GenerationControlAuditRetentionService(GenerationControlAuditStore store,
                                                   GenerationControlAuditProperties properties) {
        this(store, properties, Clock.systemUTC());
    }

    GenerationControlAuditRetentionService(GenerationControlAuditStore store,
                                            GenerationControlAuditProperties properties,
                                            Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = GenerationControlAuditProperties.CLEANUP_INTERVAL)
    public int deleteExpiredBatch() {
        int deleted = store.deleteExpired(clock.instant(), properties.getCleanupBatchSize());
        if (deleted > 0) {
            log.info("已删除过期生成控制审计事件，count: {}", deleted);
        }
        return deleted;
    }
}
