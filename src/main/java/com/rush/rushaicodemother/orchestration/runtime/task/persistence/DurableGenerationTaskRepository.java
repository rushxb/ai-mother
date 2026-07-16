package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for submission, cancellation, worker lease and recovery metadata. */
public interface DurableGenerationTaskRepository {
    void createSubmitted(GenerationTaskSubmissionRecord task);

    Optional<DurableGenerationTaskRecord> findByTaskId(String taskId);

    Optional<DurableGenerationTaskRecord> findLatestNonTerminalByAppId(Long appId);

    boolean activate(String taskId, String leaseOwner, Instant now, Instant leaseUntil);

    GenerationTaskLeaseRenewal renewLease(String taskId, String leaseOwner, Instant now, Instant leaseUntil);

    boolean requestCancellation(String taskId, String reason, Instant requestedAt);

    void complete(String taskId, GenerationTaskStatus status, String reason,
                  String leaseOwner, Instant completedAt);

    List<GenerationTaskRecoveryCandidate> findExpiredLeases(Instant now, int limit);

    boolean finalizeExpiredLease(GenerationTaskRecoveryCandidate candidate,
                                 GenerationTaskStatus terminalStatus,
                                 Instant completedAt, String reason);
}
