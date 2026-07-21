package com.rush.rushaicodemother.memory;

import java.time.Instant;
import java.util.List;

/** Durable source-of-truth bridge from relational generation traces to the Milvus derived index. */
public interface GenerationMemoryOutboxRepository {

    List<GenerationMemoryOutboxItem> claimBatch(Instant now,
                                                Instant leaseUntil,
                                                String leaseOwner,
                                                int batchSize,
                                                int maxAttempts);

    boolean markIndexed(String taskId, String leaseOwner, Instant indexedAt);

    boolean markFailed(String taskId,
                       String leaseOwner,
                       String error,
                       Instant failedAt,
                       Instant nextAttemptAt);
}
