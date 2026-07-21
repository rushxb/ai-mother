package com.rush.rushaicodemother.memory;

import java.time.Instant;
import java.util.List;

/** Durable outbox for idempotent deletion of derived semantic-memory records. */
public interface SemanticMemoryDeletionOutboxRepository {

    void enqueueApplicationDeletion(Long tenantId,
                                    Long appId,
                                    Long requestedByUserId,
                                    Instant createdAt);

    List<SemanticMemoryDeletionOutboxItem> claimBatch(Instant now,
                                                       Instant leaseUntil,
                                                       String leaseOwner,
                                                       int batchSize);

    boolean markCompleted(String operationId, String leaseOwner, Instant completedAt);

    boolean markFailed(String operationId,
                       String leaseOwner,
                       String error,
                       Instant failedAt,
                       Instant nextAttemptAt);
}
