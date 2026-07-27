package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.GenerationMemoryOutboxProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.monitor.SemanticMemoryMetricsCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 租用、重试工作程序以进行应用程序级语义内存删除。 */
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
    private final SemanticMemoryMetricsCollector metrics;

    public SemanticMemoryDeletionOutboxProcessor(
            SemanticMemoryDeletionOutboxRepository repository,
            LongTermMemoryStore memoryStore,
            GenerationMemoryOutboxProperties properties
    ) {
        this(repository, memoryStore, properties, Clock.systemUTC(),
                "memory-delete-" + UUID.randomUUID(), SemanticMemoryMetricsCollector.noOp());
    }

    @Autowired
    public SemanticMemoryDeletionOutboxProcessor(
            SemanticMemoryDeletionOutboxRepository repository,
            LongTermMemoryStore memoryStore,
            GenerationMemoryOutboxProperties properties,
            SemanticMemoryMetricsCollector metrics
    ) {
        this(repository, memoryStore, properties, Clock.systemUTC(),
                "memory-delete-" + UUID.randomUUID(), metrics);
    }

    SemanticMemoryDeletionOutboxProcessor(
            SemanticMemoryDeletionOutboxRepository repository,
            LongTermMemoryStore memoryStore,
            GenerationMemoryOutboxProperties properties,
            Clock clock,
            String leaseOwner
    ) {
        this(repository, memoryStore, properties, clock, leaseOwner,
                SemanticMemoryMetricsCollector.noOp());
    }

    SemanticMemoryDeletionOutboxProcessor(
            SemanticMemoryDeletionOutboxRepository repository,
            LongTermMemoryStore memoryStore,
            GenerationMemoryOutboxProperties properties,
            Clock clock,
            String leaseOwner,
            SemanticMemoryMetricsCollector metrics
    ) {
        this.repository = repository;
        this.memoryStore = memoryStore;
        this.properties = properties;
        this.clock = clock;
        this.leaseOwner = leaseOwner;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.memory.outbox.scan-interval:30s}")
    public void processPending() {
        processBatch();
    }

    int processBatch() {
        Instant batchStartedAt = clock.instant();
        String batchStatus = "success";
        try {
            Instant now = clock.instant();
            int completed = 0;
            var items = repository.claimBatch(
                    now,
                    now.plus(properties.getLeaseDuration()),
                    leaseOwner,
                    properties.getBatchSize());
            metrics.recordOutboxItems("deletion", "claimed", items.size());
            for (SemanticMemoryDeletionOutboxItem item : items) {
                try {
                    memoryStore.deleteByApplication(item.tenantId(), item.appId());
                    if (repository.markCompleted(item.operationId(), leaseOwner, clock.instant())) {
                        completed++;
                        metrics.recordOutboxItems("deletion", "completed", 1);
                    } else {
                        metrics.recordOutboxItems("deletion", "lease_lost", 1);
                        log.warn("Semantic-memory deletion completed after its outbox lease was lost, operationId: {}",
                                item.operationId());
                    }
                } catch (RuntimeException failure) {
                    Instant failedAt = clock.instant();
                    String diagnostic = LogExceptionSanitizer.sanitizeMessage(failure);
                    boolean recorded = repository.markFailed(
                            item.operationId(), leaseOwner, diagnostic, failedAt,
                            failedAt.plus(retryDelay(item.attempts())));
                    metrics.recordOutboxItems("deletion",
                            recorded ? "retry_scheduled" : "lease_lost", 1);
                    log.warn("Semantic-memory deletion failed, operationId: {}, attempt: {}, recorded: {}, error: {}",
                            item.operationId(), item.attempts(), recorded, diagnostic);
                }
            }
            return completed;
        } catch (RuntimeException failure) {
            batchStatus = "error";
            throw failure;
        } finally {
            Instant completedAt = clock.instant();
            metrics.recordOutboxBatch("deletion", batchStatus,
                    nonNegativeDuration(batchStartedAt, completedAt));
            refreshBacklog(completedAt);
        }
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

    private void refreshBacklog(Instant observedAt) {
        try {
            metrics.updateBacklog("deletion", repository.inspectBacklog(observedAt), observedAt);
            metrics.recordBacklogRefresh("deletion", "success");
        } catch (RuntimeException failure) {
            metrics.recordBacklogRefresh("deletion", "error");
            log.warn("Semantic-memory deletion outbox backlog refresh failed: {}",
                    LogExceptionSanitizer.sanitizeMessage(failure));
        }
    }

    private Duration nonNegativeDuration(Instant startedAt, Instant completedAt) {
        Duration duration = Duration.between(startedAt, completedAt);
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
