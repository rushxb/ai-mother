package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * Selects the truthful terminal state for an expired lease. Cancellation takes precedence because
 * it is an explicit durable user intent; an elapsed absolute deadline is classified separately;
 * every other orphan remains a non-recoverable worker failure until checkpoints are versioned.
 */
@Component
public class GenerationTaskRecoveryPolicy {

    static final String DEFAULT_CANCELLATION_REASON = "user_requested";
    static final String DEADLINE_EXCEEDED_REASON = "task_deadline_exceeded";
    static final String ORPHAN_FAILURE_REASON = "worker_lease_expired_non_recoverable";

    public GenerationTaskRecoveryDecision decide(GenerationTaskRecoveryCandidate candidate, Instant now) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(now, "now");

        if (candidate.cancellationRequested()) {
            return new GenerationTaskRecoveryDecision(
                    GenerationTaskStatus.CANCELLED,
                    normalizeReason(candidate.cancellationReason(), DEFAULT_CANCELLATION_REASON)
            );
        }
        if (candidate.deadlineAt() != null && !candidate.deadlineAt().isAfter(now)) {
            return new GenerationTaskRecoveryDecision(
                    GenerationTaskStatus.DEADLINE_EXCEEDED, DEADLINE_EXCEEDED_REASON
            );
        }
        return new GenerationTaskRecoveryDecision(
                GenerationTaskStatus.FAILED, ORPHAN_FAILURE_REASON
        );
    }

    private String normalizeReason(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason.trim();
    }
}
