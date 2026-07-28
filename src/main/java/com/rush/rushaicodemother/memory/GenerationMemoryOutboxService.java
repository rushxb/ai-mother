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

@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.memory", name = {"long-term.enabled", "outbox.enabled"},
        havingValue = "true")
/**
 * 生成记忆事务发件箱服务实现。
 */
public class GenerationMemoryOutboxService {
    private static final String OUTBOX_SOURCE = "generation_task_outbox";

    private final GenerationMemoryOutboxRepository repository;
    private final GenerationSemanticMemoryService semanticMemoryService;
    private final GenerationMemoryOutboxProperties properties;
    private final Clock clock;
    private final String leaseOwner;
    private final SemanticMemoryMetricsCollector metrics;

    public GenerationMemoryOutboxService(GenerationMemoryOutboxRepository repository,
                                         GenerationSemanticMemoryService semanticMemoryService,
                                         GenerationMemoryOutboxProperties properties) {
        this(repository, semanticMemoryService, properties, Clock.systemUTC(),
                "memory-index-" + UUID.randomUUID(), SemanticMemoryMetricsCollector.noOp());
    }

    @Autowired
    public GenerationMemoryOutboxService(GenerationMemoryOutboxRepository repository,
                                         GenerationSemanticMemoryService semanticMemoryService,
                                         GenerationMemoryOutboxProperties properties,
                                         SemanticMemoryMetricsCollector metrics) {
        this(repository, semanticMemoryService, properties, Clock.systemUTC(),
                "memory-index-" + UUID.randomUUID(), metrics);
    }

    GenerationMemoryOutboxService(GenerationMemoryOutboxRepository repository,
                                  GenerationSemanticMemoryService semanticMemoryService,
                                  GenerationMemoryOutboxProperties properties,
                                  Clock clock) {
        this(repository, semanticMemoryService, properties, clock, "memory-index-test",
                SemanticMemoryMetricsCollector.noOp());
    }

    GenerationMemoryOutboxService(GenerationMemoryOutboxRepository repository,
                                  GenerationSemanticMemoryService semanticMemoryService,
                                  GenerationMemoryOutboxProperties properties,
                                  Clock clock,
                                  String leaseOwner) {
        this(repository, semanticMemoryService, properties, clock, leaseOwner,
                SemanticMemoryMetricsCollector.noOp());
    }

    GenerationMemoryOutboxService(GenerationMemoryOutboxRepository repository,
                                  GenerationSemanticMemoryService semanticMemoryService,
                                  GenerationMemoryOutboxProperties properties,
                                  Clock clock,
                                  String leaseOwner,
                                  SemanticMemoryMetricsCollector metrics) {
        this.repository = repository;
        this.semanticMemoryService = semanticMemoryService;
        this.properties = properties;
        this.clock = clock;
        this.leaseOwner = leaseOwner;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.memory.outbox.scan-interval:30s}")
    public void processPending() {
        processBatch();
    }

    /** 处理批次。 */
    int processBatch() {
        Instant batchStartedAt = clock.instant();
        String batchStatus = "success";
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            Instant now = clock.instant();
            int indexed = 0;
            var items = repository.claimBatch(
                    now,
                    now.plus(properties.getLeaseDuration()),
                    leaseOwner,
                    properties.getBatchSize(),
                    properties.getMaxAttempts());
            metrics.recordOutboxItems("generation", "claimed", items.size());
            for (GenerationMemoryOutboxItem item : items) {
                try {
                    GenerationOutcomeMemoryRequest request = item.toMemoryRequest();
                    GenerationOutcomeMemoryDocument document =
                            GenerationOutcomeMemoryDocument.from(request, OUTBOX_SOURCE);
                    semanticMemoryService.rememberNow(
                            request.tenantId(), request.appId(), request.userId(), request.taskId(),
                            document.type(), document.content(), document.metadata()
                    );
                    if (repository.markIndexed(item.taskId(), leaseOwner, clock.instant())) {
                        indexed++;
                        metrics.recordOutboxItems("generation", "completed", 1);
                    } else {
                        metrics.recordOutboxItems("generation", "lease_lost", 1);
                        log.warn("Generation memory indexed after its outbox lease was lost, taskId: {}",
                                item.taskId());
                    }
                } catch (RuntimeException failure) {
                    Instant failedAt = clock.instant();
                    String diagnostic = LogExceptionSanitizer.sanitizeMessage(failure);
                    boolean recorded = repository.markFailed(
                            item.taskId(), leaseOwner, diagnostic, failedAt,
                            failedAt.plus(retryDelay(item.attempts())));
                    if (!recorded) {
                        metrics.recordOutboxItems("generation", "lease_lost", 1);
                    } else if (item.attempts() >= properties.getMaxAttempts()) {
                        metrics.recordOutboxItems("generation", "dead_letter", 1);
                    } else {
                        metrics.recordOutboxItems("generation", "retry_scheduled", 1);
                    }
                    log.warn("Generation memory outbox indexing failed, taskId: {}, attempt: {}, recorded: {}, error: {}",
                            item.taskId(), item.attempts(), recorded, diagnostic);
                }
            }
            return indexed;
        } catch (RuntimeException failure) {
            batchStatus = "error";
            throw failure;
        } finally {
            Instant completedAt = clock.instant();
            metrics.recordOutboxBatch("generation", batchStatus,
                    nonNegativeDuration(batchStartedAt, completedAt));
            refreshBacklog(completedAt);
        }
    }

    /** 根据当前尝试次数计算有上限的重试延迟。 */
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

    /** 刷新积压量。 */
    private void refreshBacklog(Instant observedAt) {
        try {
            metrics.updateBacklog("generation", repository.inspectBacklog(
                    observedAt, properties.getMaxAttempts()), observedAt);
            metrics.recordBacklogRefresh("generation", "success");
        } catch (RuntimeException failure) {
            metrics.recordBacklogRefresh("generation", "error");
            log.warn("Generation memory outbox backlog refresh failed: {}",
                    LogExceptionSanitizer.sanitizeMessage(failure));
        }
    }

    private Duration nonNegativeDuration(Instant startedAt, Instant completedAt) {
        Duration duration = Duration.between(startedAt, completedAt);
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
