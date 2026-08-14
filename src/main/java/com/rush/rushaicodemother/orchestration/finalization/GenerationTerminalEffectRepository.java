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

    /** 在当前租约与执行轮次下持久单个副作用回执。 */
    boolean markOperationCompleted(String taskId,
                                   long executionEpoch,
                                   String leaseOwner,
                                   GenerationTerminalEffectOperation operation,
                                   Instant completedAt);

    boolean markFailed(String taskId,
                       long executionEpoch,
                       String leaseOwner,
                       String error,
                       Instant failedAt,
                       Instant nextAttemptAt);

    GenerationTerminalEffectBacklog inspectBacklog(Instant now, int maxAttempts);

    List<GenerationTerminalEffectAdminItem> listOutstanding(Instant now,
                                                             int maxAttempts,
                                                             int limit);

    /** 仅重放已耗尽重试、没有活动租约且仍未完成的精确执行轮次。 */
    boolean replayDeadLetter(String taskId,
                             long executionEpoch,
                             long operatorUserId,
                             Instant requestedAt,
                             int maxAttempts);
}
