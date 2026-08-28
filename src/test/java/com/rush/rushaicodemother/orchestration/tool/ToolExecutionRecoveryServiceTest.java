package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.testing.GenerationFailureEvidence;
import com.rush.rushaicodemother.testing.GenerationFailureMatrix;
import com.rush.rushaicodemother.config.AiToolApprovalProperties;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag(GenerationFailureMatrix.TAG)
class ToolExecutionRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Test
    void staleExecutingInvocationMustResetBeforeTaskReturnsToWaiting() {
        ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);
        DurableGenerationTaskRepository tasks = mock(DurableGenerationTaskRepository.class);
        AiToolApprovalProperties properties = new AiToolApprovalProperties();
        ToolApprovalRecord executing = approval(
                ToolApprovalStatus.EXECUTING, NOW.minusSeconds(60), null, 1, 4);
        ToolApprovalRecord approved = approval(
                ToolApprovalStatus.APPROVED, null, null, 1, 5);
        GenerationTaskRecoveryCandidate candidate = candidate();
        when(approvals.findRecoverableExecution("task-1")).thenReturn(Optional.of(executing));
        when(approvals.resetStaleExecution(
                "task-1", 1L, DestructiveToolAction.SNAPSHOT_ROLLBACK,
                "a".repeat(64), 4)).thenReturn(true);
        when(approvals.find(
                "task-1", 1L, DestructiveToolAction.SNAPSHOT_ROLLBACK,
                "a".repeat(64))).thenReturn(Optional.of(approved));
        when(tasks.restoreWaitingAfterStaleToolExecution(
                candidate, "tool_execution_recovery", NOW)).thenReturn(true);
        ToolExecutionRecoveryService service = new ToolExecutionRecoveryService(
                approvals, tasks, properties);

        ToolApprovalRecord recovered = service.recover(candidate, NOW).orElseThrow();

        assertEquals(ToolApprovalStatus.APPROVED, recovered.status());
        verify(approvals).resetStaleExecution(
                "task-1", 1L, DestructiveToolAction.SNAPSHOT_ROLLBACK,
                "a".repeat(64), 4);
        verify(tasks).restoreWaitingAfterStaleToolExecution(
                candidate, "tool_execution_recovery", NOW);
    }

    @Test
    @GenerationFailureEvidence("recovery_consumed_tool_receipt_preserved")
    void consumedInvocationMustOnlyRestoreTaskAndPreserveReplayResult() {
        ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);
        DurableGenerationTaskRepository tasks = mock(DurableGenerationTaskRepository.class);
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(false, "completed");
        ToolApprovalRecord consumed = approval(
                ToolApprovalStatus.CONSUMED, NOW.minusSeconds(30), outcome, 1, 5);
        GenerationTaskRecoveryCandidate candidate = candidate();
        assertEquals("task-1", candidate.taskId());
        assertEquals(1L, candidate.executionEpoch());
        assertEquals("a".repeat(64), consumed.approvalId());
        assertEquals("call-1", consumed.invocationCheckpoint().requestId());
        when(approvals.findRecoverableExecution("task-1")).thenReturn(Optional.of(consumed));
        when(tasks.restoreWaitingAfterStaleToolExecution(
                candidate, "tool_execution_recovery", NOW)).thenReturn(true);
        ToolExecutionRecoveryService service = new ToolExecutionRecoveryService(
                approvals, tasks, new AiToolApprovalProperties());

        ToolApprovalRecord recovered = service.recover(candidate, NOW).orElseThrow();

        assertEquals(outcome, recovered.executionOutcome());
        verify(approvals, never()).resetStaleExecution(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(tasks).restoreWaitingAfterStaleToolExecution(
                candidate, "tool_execution_recovery", NOW);
    }

    private GenerationTaskRecoveryCandidate candidate() {
        return new GenerationTaskRecoveryCandidate(
                "task-1", 11L, GenerationTaskStatus.RUNNING, "dead-worker",
                NOW.minusSeconds(1), NOW.plusSeconds(600), false, null, 1L, 8);
    }

    private ToolApprovalRecord approval(
            ToolApprovalStatus status,
            Instant executionStartedAt,
            ToolExecutionOutcome outcome,
            int executionAttempt,
            long version
    ) {
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                "call-1", "manageSnapshot", "{}", "{\"taskId\":\"task-1\"}", NOW);
        return new ToolApprovalRecord(
                "a".repeat(64), "task-1", 1L, 11L, 7L,
                DestructiveToolAction.SNAPSHOT_ROLLBACK, "{}", status,
                NOW.minusSeconds(120), NOW.plusSeconds(600), 7L, NOW.minusSeconds(90),
                status == ToolApprovalStatus.CONSUMED ? NOW.minusSeconds(20) : null,
                version, checkpoint, executionStartedAt, outcome, executionAttempt);
    }
}
