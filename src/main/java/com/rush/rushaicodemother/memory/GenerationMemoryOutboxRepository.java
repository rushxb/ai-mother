package com.rush.rushaicodemother.memory;

import java.time.Instant;
import java.util.List;

/** 从关系生成痕迹到 Milvus 派生索引的持久真相来源桥梁。 */
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

    SemanticMemoryOutboxBacklog inspectBacklog(Instant now, int maxAttempts);
}
