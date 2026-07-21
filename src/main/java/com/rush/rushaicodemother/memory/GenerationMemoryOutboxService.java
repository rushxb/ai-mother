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
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.memory.outbox", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class GenerationMemoryOutboxService {
    private final GenerationMemoryOutboxRepository repository;
    private final GenerationSemanticMemoryService semanticMemoryService;
    private final GenerationMemoryOutboxProperties properties;
    private final Clock clock;
    private final String leaseOwner;

    public GenerationMemoryOutboxService(GenerationMemoryOutboxRepository repository,
                                         GenerationSemanticMemoryService semanticMemoryService,
                                         GenerationMemoryOutboxProperties properties) {
        this(repository, semanticMemoryService, properties, Clock.systemUTC(),
                "memory-index-" + UUID.randomUUID());
    }

    GenerationMemoryOutboxService(GenerationMemoryOutboxRepository repository,
                                  GenerationSemanticMemoryService semanticMemoryService,
                                  GenerationMemoryOutboxProperties properties,
                                  Clock clock) {
        this(repository, semanticMemoryService, properties, clock, "memory-index-test");
    }

    GenerationMemoryOutboxService(GenerationMemoryOutboxRepository repository,
                                  GenerationSemanticMemoryService semanticMemoryService,
                                  GenerationMemoryOutboxProperties properties,
                                  Clock clock,
                                  String leaseOwner) {
        this.repository = repository;
        this.semanticMemoryService = semanticMemoryService;
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
        int indexed = 0;
        for (GenerationMemoryOutboxItem item : repository.claimBatch(
                now,
                now.plus(properties.getLeaseDuration()),
                leaseOwner,
                properties.getBatchSize(),
                properties.getMaxAttempts())) {
            try {
                MemoryType memoryType = item.status() == com.rush.rushaicodemother.model.enums.GenerationTaskStatus.SUCCESS
                        ? MemoryType.TASK_OUTCOME
                        : MemoryType.FAILURE_LESSON;
                semanticMemoryService.rememberNow(
                        item.tenantId(), item.appId(), item.userId(), item.taskId(),
                        memoryType, item.memorySummary(),
                        Map.of("source", "generation_task_outbox", "taskStatus", item.status().getValue())
                );
                if (repository.markIndexed(item.taskId(), leaseOwner, clock.instant())) {
                    indexed++;
                } else {
                    log.warn("Generation memory indexed after its outbox lease was lost, taskId: {}",
                            item.taskId());
                }
            } catch (RuntimeException failure) {
                Instant failedAt = clock.instant();
                String diagnostic = LogExceptionSanitizer.sanitizeMessage(failure);
                boolean recorded = repository.markFailed(
                        item.taskId(), leaseOwner, diagnostic, failedAt,
                        failedAt.plus(retryDelay(item.attempts())));
                log.warn("Generation memory outbox indexing failed, taskId: {}, attempt: {}, recorded: {}, error: {}",
                        item.taskId(), item.attempts(), recorded, diagnostic);
            }
        }
        return indexed;
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
