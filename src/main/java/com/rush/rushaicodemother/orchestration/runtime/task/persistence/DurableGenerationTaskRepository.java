package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 用于提交、取消、工作器租赁和恢复元数据的持久性端口。 */
public interface DurableGenerationTaskRepository {
    void createSubmitted(GenerationTaskSubmissionRecord task);

    Optional<DurableGenerationTaskRecord> findByTaskId(String taskId);

    Optional<DurableGenerationTaskRecord> findLatestNonTerminalByAppId(Long appId);

    Optional<GenerationTaskCommand> findCommandByTaskId(String taskId);

    boolean isCurrentFence(GenerationExecutionFence fence, Instant now);

    GenerationTaskLoadSnapshot loadCurrentLoad();

    Optional<GenerationTaskLease> reserveQueued(String taskId, String leaseOwner,
                                                Instant now, Instant leaseUntil);

    boolean activate(GenerationTaskLease lease, Instant now, Instant leaseUntil);

    boolean releaseClaimToQueue(GenerationTaskLease lease, Instant releasedAt, String reason);

    GenerationTaskLeaseRenewal renewLease(GenerationTaskLease lease, Instant now, Instant leaseUntil);

    boolean suspendForApproval(GenerationTaskLease lease, String stageMessage, Instant suspendedAt);

    Optional<GenerationTaskLease> requeueAfterApproval(String taskId, String leaseOwner,
                                                       Instant now, Instant leaseUntil);

    boolean restoreWaitingAfterDispatchFailure(GenerationTaskLease lease,
                                               String stageMessage, Instant restoredAt);

    boolean restoreWaitingAfterStaleToolExecution(GenerationTaskRecoveryCandidate candidate,
                                                  String stageMessage,
                                                  Instant restoredAt);

    boolean requestCancellation(String taskId, String reason, Instant requestedAt);

    void completeOwned(GenerationTaskLease lease, GenerationTaskStatus status,
                       String reason, Instant completedAt);

    void completeUnowned(String taskId, GenerationTaskStatus status,
                         String reason, Instant completedAt);

    List<GenerationTaskRecoveryCandidate> findExpiredLeases(Instant now, int limit);

    boolean finalizeExpiredLease(GenerationTaskRecoveryCandidate candidate,
                                 GenerationTaskStatus terminalStatus,
                                 Instant completedAt, String reason);

    boolean requeueExpiredLease(GenerationTaskRecoveryCandidate candidate,
                                Instant requeuedAt,
                                String reason);

    List<String> findDispatchableQueuedTaskIds(Instant now, Instant dispatchedBefore, int limit);

    void recordDispatchSuccess(String taskId, Instant dispatchedAt);

    void recordDispatchFailure(String taskId, String error, Instant failedAt);
}
