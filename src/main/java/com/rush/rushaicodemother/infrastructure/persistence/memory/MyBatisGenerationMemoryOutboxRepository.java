package com.rush.rushaicodemother.infrastructure.persistence.memory;

import com.rush.rushaicodemother.mapper.GenerationMemoryOutboxMapper;
import com.rush.rushaicodemother.memory.GenerationMemoryOutboxItem;
import com.rush.rushaicodemother.memory.GenerationMemoryOutboxRepository;
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
        List<GenerationTask> candidates = mapper.selectPending(claimedAt, batchSize, maxAttempts);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<GenerationMemoryOutboxItem> claimed = new ArrayList<>();
        for (GenerationTask candidate : candidates) {
            int attempts = candidate.getMemoryIndexAttempts() == null
                    ? 0
                    : candidate.getMemoryIndexAttempts();
            if (mapper.claim(candidate.getTaskId(), attempts, maxAttempts,
                    leaseOwner, claimedAt, claimedUntil) == 1) {
                GenerationTaskStatus status = GenerationTaskStatus.fromValue(candidate.getStatus());
                if (status != null) {
                    claimed.add(new GenerationMemoryOutboxItem(
                            candidate.getTaskId(), candidate.getTenantId(), candidate.getAppId(),
                            candidate.getUserId(), status,
                            candidate.getMemorySummary(), attempts + 1
                    ));
                }
            }
        }
        return List.copyOf(claimed);
    }

    @Override
    public boolean markIndexed(String taskId, String leaseOwner, Instant indexedAt) {
        validateTransition(taskId, leaseOwner, indexedAt);
        return mapper.markIndexed(taskId, leaseOwner,
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
                taskId, leaseOwner, normalizedError,
                LocalDateTime.ofInstant(failedAt, databaseZone),
                LocalDateTime.ofInstant(nextAttemptAt, databaseZone)) == 1;
    }

    private void validateTransition(String taskId, String leaseOwner, Instant changedAt) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")
                || leaseOwner == null || leaseOwner.isBlank()
                || leaseOwner.length() > MAX_LEASE_OWNER_LENGTH
                || changedAt == null) {
            throw new IllegalArgumentException("memory outbox transition arguments are invalid");
        }
    }
}
