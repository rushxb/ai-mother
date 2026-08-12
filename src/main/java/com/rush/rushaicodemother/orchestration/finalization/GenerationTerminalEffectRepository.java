package com.rush.rushaicodemother.orchestration.finalization;

import java.time.Instant;
import java.util.List;

/** generation_task 单行终态副作用 outbox 的持久化端口。 */
public interface GenerationTerminalEffectRepository {

    List<GenerationTerminalEffect> claimBatch(Instant now,
                                               Instant leaseUntil,
                                               String leaseOwner,
                                               int limit,
                                               int maxAttempts);

    boolean markCompleted(String taskId, long executionEpoch, String leaseOwner, Instant completedAt);

    boolean markFailed(String taskId,
                       long executionEpoch,
                       String leaseOwner,
                       String error,
                       Instant failedAt,
                       Instant nextAttemptAt);
}
