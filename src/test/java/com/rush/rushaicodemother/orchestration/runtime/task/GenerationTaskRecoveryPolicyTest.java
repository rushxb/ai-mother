package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationTaskRecoveryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-16T04:00:00Z");
    private final GenerationTaskRecoveryPolicy policy = new GenerationTaskRecoveryPolicy();

    @Test
    void durableCancellationMustTakePrecedenceOverDeadline() {
        GenerationTaskRecoveryDecision decision = policy.decide(candidate(
                NOW.minusSeconds(30), true, "admin_requested"
        ), NOW);

        assertEquals(GenerationTaskStatus.CANCELLED, decision.status());
        assertEquals("admin_requested", decision.reason());
    }

    @Test
    void elapsedAbsoluteDeadlineMustRemainDistinguishableFromWorkerFailure() {
        GenerationTaskRecoveryDecision decision = policy.decide(candidate(
                NOW.minusSeconds(1), false, null
        ), NOW);

        assertEquals(GenerationTaskStatus.DEADLINE_EXCEEDED, decision.status());
        assertEquals(GenerationTaskRecoveryPolicy.DEADLINE_EXCEEDED_REASON, decision.reason());
    }

    @Test
    void orphanWithoutCancellationOrElapsedDeadlineMustFailHonestly() {
        GenerationTaskRecoveryDecision decision = policy.decide(candidate(
                NOW.plusSeconds(60), false, null
        ), NOW);

        assertEquals(GenerationTaskStatus.FAILED, decision.status());
        assertEquals(GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON, decision.reason());
    }

    @Test
    void blankCancellationReasonMustUseStableFallback() {
        GenerationTaskRecoveryDecision decision = policy.decide(candidate(
                null, true, "  "
        ), NOW);

        assertEquals(GenerationTaskStatus.CANCELLED, decision.status());
        assertEquals(GenerationTaskRecoveryPolicy.DEFAULT_CANCELLATION_REASON, decision.reason());
    }

    private GenerationTaskRecoveryCandidate candidate(Instant deadlineAt,
                                                       boolean cancellationRequested,
                                                       String cancellationReason) {
        return new GenerationTaskRecoveryCandidate(
                "task-expired", 1L, GenerationTaskStatus.RUNNING,
                "lost-worker", NOW.minusSeconds(1), deadlineAt,
                cancellationRequested, cancellationReason, 7L
        );
    }
}
