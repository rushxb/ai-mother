package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.GenerationMemoryOutboxProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Leased, retrying worker for application-level semantic-memory deletion. */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.memory", name = {"long-term.enabled", "outbox.enabled"},
        havingValue = "true")
public class SemanticMemoryDeletionOutboxProcessor {

    private final SemanticMemoryDeletionOutboxRepository repository;
    private final LongTermMemoryStore memoryStore;
    private final GenerationMemoryOutboxProperties properties;
    private final Clock clock;
    private final String leaseOwner;

    public SemanticMemoryDeletionOutboxProcessor(
            SemanticMemoryDeletionOutboxRepository repository,
            LongTermMemoryStore memoryStore,
            GenerationMemoryOutboxProperties properties
    ) {
        this(repository, memoryStore, properties, Clock.systemUTC(),
                "memory-delete-" + UUID.randomUUID());
    }

    SemanticMemoryDeletionOutboxProcessor(
            SemanticMemoryDeletionOutboxRepository repository,
            LongTermMemoryStore memoryStore,
            GenerationMemoryOutboxProperties properties,
            Clock clock,
            String leaseOwner
    ) {
        this.repository = repository;
        this.memoryStore = memoryStore;
        this.properties = properties;
        this.clock = clock;
        this.leaseOwner = leaseOwner;
    }

    @Scheduled(fixedDelayString = "${app.memory.outbox.scan-interval:30s}")
    public void processPending() {
        processBatch();
    }

    int processBatch() {
        Instant now = clock.instant();
        int completed = 0;
        for (SemanticMemoryDeletionOutboxItem item : repository.claimBatch(
                now,
                now.plus(properties.getLeaseDuration()),
                leaseOwner,
                properties.getBatchSize())) {
            try {
                memoryStore.deleteByApplication(item.tenantId(), item.appId());
                if (repository.markCompleted(item.operationId(), leaseOwner, clock.instant())) {
                    completed++;
                } else {
                    log.warn("Semantic-memory deletion completed after its outbox lease was lost, operationId: {}",
                            item.operationId());
                }
            } catch (RuntimeException failure) {
                Instant failedAt = clock.instant();
                String diagnostic = LogExceptionSanitizer.sanitizeMessage(failure);
                boolean recorded = repository.markFailed(
                        item.operationId(), leaseOwner, diagnostic, failedAt,
                        failedAt.plus(retryDelay(item.attempts())));
                log.warn("Semantic-memory deletion failed, operationId: {}, attempt: {}, recorded: {}, error: {}",
                        item.operationId(), item.attempts(), recorded, diagnostic);
            }
        }
        return completed;
    }

    private Duration retryDelay(int attempts) {
        int exponent = Math.max(0, Math.min(20, attempts - 1));
        Duration candidate;
        try {
            candidate = properties.getInitialRetryDelay().multipliedBy(1L << exponent);
        } catch (ArithmeticException overflow) {
            return properties.getMaxRetryDelay();
        }
        return candidate.compareTo(properties.getMaxRetryDelay()) > 0
                ? properties.getMaxRetryDelay()
                : candidate;
    }
}
