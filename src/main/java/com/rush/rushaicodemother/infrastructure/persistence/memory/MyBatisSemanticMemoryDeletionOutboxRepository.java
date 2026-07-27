package com.rush.rushaicodemother.infrastructure.persistence.memory;

import cn.hutool.crypto.digest.DigestUtil;
import com.rush.rushaicodemother.mapper.SemanticMemoryDeletionOutboxMapper;
import com.rush.rushaicodemother.mapper.projection.SemanticMemoryOutboxBacklogRow;
import com.rush.rushaicodemother.memory.SemanticMemoryDeletionOutboxItem;
import com.rush.rushaicodemother.memory.SemanticMemoryDeletionOutboxRepository;
import com.rush.rushaicodemother.memory.SemanticMemoryOutboxBacklog;
import com.rush.rushaicodemother.model.entity.SemanticMemoryDeletionOutboxEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis语义记忆删除事务发件箱持久化仓储。
 */
@Repository
@RequiredArgsConstructor
public class MyBatisSemanticMemoryDeletionOutboxRepository
        implements SemanticMemoryDeletionOutboxRepository {

    private static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_ERROR_LENGTH = 1_000;
    private static final int MAX_LEASE_OWNER_LENGTH = 128;

    private final SemanticMemoryDeletionOutboxMapper mapper;
    private final ZoneId databaseZone = ZoneId.systemDefault();

    @Override
    public void enqueueApplicationDeletion(Long tenantId,
                                           Long appId,
                                           Long requestedByUserId,
                                           Instant createdAt) {
        requirePositive(tenantId, "tenantId");
        requirePositive(appId, "appId");
        requirePositive(requestedByUserId, "requestedByUserId");
        requireInstant(createdAt, "createdAt");
        String operationId = DigestUtil.sha256Hex(
                "DELETE_APPLICATION:" + tenantId + ":" + appId);
        mapper.enqueue(operationId, tenantId, appId, requestedByUserId, toLocal(createdAt));
    }

    @Override
    @Transactional
    public List<SemanticMemoryDeletionOutboxItem> claimBatch(Instant now,
                                                              Instant leaseUntil,
                                                              String leaseOwner,
                                                              int batchSize) {
        requireInstant(now, "now");
        requireInstant(leaseUntil, "leaseUntil");
        if (!leaseUntil.isAfter(now) || batchSize <= 0 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("semantic-memory deletion outbox claim is invalid");
        }
        requireLeaseOwner(leaseOwner);
        LocalDateTime claimedAt = toLocal(now);
        LocalDateTime claimedUntil = toLocal(leaseUntil);
        List<SemanticMemoryDeletionOutboxEntity> candidates =
                mapper.selectPending(claimedAt, batchSize);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<SemanticMemoryDeletionOutboxItem> claimed = new ArrayList<>();
        for (SemanticMemoryDeletionOutboxEntity candidate : candidates) {
            int attempts = candidate.getAttempts() == null ? 0 : candidate.getAttempts();
            if (mapper.claim(candidate.getOperationId(), attempts, leaseOwner,
                    claimedAt, claimedUntil) == 1) {
                claimed.add(new SemanticMemoryDeletionOutboxItem(
                        candidate.getOperationId(), candidate.getTenantId(), candidate.getAppId(),
                        candidate.getRequestedByUserId(), attempts + 1));
            }
        }
        return List.copyOf(claimed);
    }

    @Override
    public boolean markCompleted(String operationId, String leaseOwner, Instant completedAt) {
        requireOperationId(operationId);
        requireLeaseOwner(leaseOwner);
        requireInstant(completedAt, "completedAt");
        return mapper.markCompleted(operationId, leaseOwner, toLocal(completedAt)) == 1;
    }

    @Override
    public boolean markFailed(String operationId,
                              String leaseOwner,
                              String error,
                              Instant failedAt,
                              Instant nextAttemptAt) {
        requireOperationId(operationId);
        requireLeaseOwner(leaseOwner);
        requireInstant(failedAt, "failedAt");
        requireInstant(nextAttemptAt, "nextAttemptAt");
        if (!nextAttemptAt.isAfter(failedAt)) {
            throw new IllegalArgumentException("semantic-memory deletion retry must be in the future");
        }
        return mapper.markFailed(operationId, leaseOwner, normalizeError(error),
                toLocal(failedAt), toLocal(nextAttemptAt)) == 1;
    }

    @Override
    public SemanticMemoryOutboxBacklog inspectBacklog(Instant now) {
        requireInstant(now, "now");
        SemanticMemoryOutboxBacklogRow row = mapper.inspectBacklog(toLocal(now));
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

    private LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, databaseZone);
    }

    private String normalizeError(String error) {
        if (error == null) {
            return "";
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    private void requireOperationId(String operationId) {
        if (operationId == null || !operationId.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("semantic-memory deletion operationId is invalid");
        }
    }

    private void requireLeaseOwner(String leaseOwner) {
        if (leaseOwner == null || leaseOwner.isBlank()
                || leaseOwner.length() > MAX_LEASE_OWNER_LENGTH) {
            throw new IllegalArgumentException("semantic-memory deletion leaseOwner is invalid");
        }
    }

    private void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private void requireInstant(Instant value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private long count(Long value) {
        return value == null ? 0 : value;
    }
}
