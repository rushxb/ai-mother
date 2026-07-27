package com.rush.rushaicodemother.infrastructure.persistence.memory;

import com.rush.rushaicodemother.mapper.GenerationMemoryOutboxMapper;
import com.rush.rushaicodemother.mapper.projection.SemanticMemoryOutboxBacklogRow;
import com.rush.rushaicodemother.memory.GenerationMemoryOutboxItem;
import com.rush.rushaicodemother.memory.GenerationMemoryOutboxRepository;
import com.rush.rushaicodemother.memory.SemanticMemoryContract;
import com.rush.rushaicodemother.memory.SemanticMemoryOutboxBacklog;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis生成记忆事务发件箱持久化仓储。
 */
@Repository
@RequiredArgsConstructor
public class MyBatisGenerationMemoryOutboxRepository implements GenerationMemoryOutboxRepository {
    private static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_LEASE_OWNER_LENGTH = 128;
    private static final int MAX_ERROR_LENGTH = 1_000;

    private final GenerationMemoryOutboxMapper mapper;
    private final ZoneId databaseZone = ZoneId.systemDefault();

    @Override
    @Transactional
    public List<GenerationMemoryOutboxItem> claimBatch(Instant now,
                                                       Instant leaseUntil,
                                                       String leaseOwner,
                                                       int batchSize,
                                                       int maxAttempts) {
        if (now == null || leaseUntil == null || !leaseUntil.isAfter(now)
                || leaseOwner == null || leaseOwner.isBlank()
                || leaseOwner.length() > MAX_LEASE_OWNER_LENGTH
                || batchSize <= 0 || batchSize > MAX_BATCH_SIZE || maxAttempts <= 0) {
            throw new IllegalArgumentException("memory outbox claim arguments are invalid");
        }
        LocalDateTime claimedAt = LocalDateTime.ofInstant(now, databaseZone);
        LocalDateTime claimedUntil = LocalDateTime.ofInstant(leaseUntil, databaseZone);
        int contractVersion = SemanticMemoryContract.INDEX_VERSION;
        List<GenerationTask> candidates = mapper.selectPending(
                claimedAt, batchSize, maxAttempts, contractVersion);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<GenerationMemoryOutboxItem> claimed = new ArrayList<>();
        for (GenerationTask candidate : candidates) {
            int attempts = candidate.getMemoryIndexAttempts() == null
                    ? 0
                    : candidate.getMemoryIndexAttempts();
            boolean contractUpgrade = candidate.getMemoryIndexContractVersion() == null
                    || candidate.getMemoryIndexContractVersion() != contractVersion;
            if (mapper.claim(candidate.getTaskId(), attempts, maxAttempts, contractVersion,
                    leaseOwner, claimedAt, claimedUntil) == 1) {
                GenerationTaskStatus status = GenerationTaskStatus.fromValue(candidate.getStatus());
                if (status != null) {
                    claimed.add(new GenerationMemoryOutboxItem(
                            candidate.getTaskId(), candidate.getTenantId(), candidate.getAppId(),
                            candidate.getUserId(), status,
                            candidate.getUserPrompt(), candidate.getMemorySummary(),
                            candidate.getOrchestrationMode(), candidate.getTargetCodeGenType(),
                            contractUpgrade ? 1 : attempts + 1
                    ));
                }
            }
        }
        return List.copyOf(claimed);
    }

    @Override
    public boolean markIndexed(String taskId, String leaseOwner, Instant indexedAt) {
        validateTransition(taskId, leaseOwner, indexedAt);
        return mapper.markIndexed(taskId, leaseOwner, SemanticMemoryContract.INDEX_VERSION,
                LocalDateTime.ofInstant(indexedAt, databaseZone)) == 1;
    }

    @Override
    public boolean markFailed(String taskId,
                              String leaseOwner,
                              String error,
                              Instant failedAt,
                              Instant nextAttemptAt) {
        validateTransition(taskId, leaseOwner, failedAt);
        if (nextAttemptAt == null || !nextAttemptAt.isAfter(failedAt)) {
            throw new IllegalArgumentException("memory outbox retry timestamp is invalid");
        }
        String normalizedError = error == null ? ""
                : error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
        return mapper.markFailed(
                taskId, leaseOwner, SemanticMemoryContract.INDEX_VERSION, normalizedError,
                LocalDateTime.ofInstant(failedAt, databaseZone),
                LocalDateTime.ofInstant(nextAttemptAt, databaseZone)) == 1;
    }

    @Override
    public SemanticMemoryOutboxBacklog inspectBacklog(Instant now, int maxAttempts) {
        if (now == null || maxAttempts <= 0) {
            throw new IllegalArgumentException("memory outbox backlog arguments are invalid");
        }
        SemanticMemoryOutboxBacklogRow row = mapper.inspectBacklog(
                LocalDateTime.ofInstant(now, databaseZone), maxAttempts,
                SemanticMemoryContract.INDEX_VERSION);
        return toBacklog(row);
    }

    private void validateTransition(String taskId, String leaseOwner, Instant changedAt) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")
                || leaseOwner == null || leaseOwner.isBlank()
                || leaseOwner.length() > MAX_LEASE_OWNER_LENGTH
                || changedAt == null) {
            throw new IllegalArgumentException("memory outbox transition arguments are invalid");
        }
    }

    private SemanticMemoryOutboxBacklog toBacklog(SemanticMemoryOutboxBacklogRow row) {
        if (row == null) {
            return SemanticMemoryOutboxBacklog.empty();
        }
        return new SemanticMemoryOutboxBacklog(
                count(row.getPending()),
                count(row.getRetrying()),
                count(row.getLeased()),
                count(row.getDeadLetter()),
                row.getOldestPendingAt() == null
                        ? null
                        : row.getOldestPendingAt().atZone(databaseZone).toInstant()
        );
    }

    private long count(Long value) {
        return value == null ? 0 : value;
    }
}
