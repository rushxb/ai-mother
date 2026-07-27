package com.rush.rushaicodemother.orchestration.dag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationDagCheckpointRecoveryPolicyTest {

    @Test
    void initialCheckpointMustBeRestartable() {
        GenerationOrchestrationTask task = runningTask();

        GenerationDagCheckpointRecoveryPolicy.Assessment assessment =
                GenerationDagCheckpointRecoveryPolicy.assess(task);

        assertTrue(assessment.automaticallyRecoverable());
        assertEquals(GenerationDagCheckpointRecoveryPolicy.Disposition.INITIAL_RESTART,
                assessment.disposition());
    }

    @Test
    void completedNodeBoundaryMustBeResumable() {
        GenerationOrchestrationTask task = runningTask();
        task.setRuntimeState(AgentRuntimeState.RUNNING);
        task.setDagFingerprint("a".repeat(64));
        task.setLastCompletedNode("planner");
        task.setCheckpointVersion(1L);
        task.getNodeStatuses().put("planner", "done");

        GenerationDagCheckpointRecoveryPolicy.Assessment assessment =
                GenerationDagCheckpointRecoveryPolicy.assess(task);

        assertTrue(assessment.automaticallyRecoverable());
        assertEquals(GenerationDagCheckpointRecoveryPolicy.Disposition.NODE_BOUNDARY_RESUME,
                assessment.disposition());
    }

    @Test
    void successfulDagTerminalStateMustBeReusableByTheModelPhase() {
        GenerationOrchestrationTask task = runningTask();
        task.setStatus("completed");
        task.setRuntimeState(AgentRuntimeState.COMPLETED);
        task.setDagFingerprint("b".repeat(64));
        task.setLastCompletedNode("review");
        task.setCheckpointVersion(2L);
        task.setTerminationReason("success");
        task.getNodeStatuses().put("review", "done");

        GenerationDagCheckpointRecoveryPolicy.Assessment assessment =
                GenerationDagCheckpointRecoveryPolicy.assess(task);

        assertTrue(assessment.automaticallyRecoverable());
        assertEquals(GenerationDagCheckpointRecoveryPolicy.Disposition.COMPLETED_REUSE,
                assessment.disposition());
    }

    @Test
    void runningNodeMustRemainAmbiguousWithoutAnIdempotencyContract() {
        GenerationOrchestrationTask task = runningTask();
        task.setRuntimeState(AgentRuntimeState.RUNNING);
        task.setCurrentNode("code");
        task.getNodeStatuses().put("code", "running");

        GenerationDagCheckpointRecoveryPolicy.Assessment assessment =
                GenerationDagCheckpointRecoveryPolicy.assess(task);

        assertFalse(assessment.automaticallyRecoverable());
        assertEquals(GenerationDagCheckpointRecoveryPolicy.Disposition.AMBIGUOUS_NODE,
                assessment.disposition());
    }

    @Test
    void failedNodeMustRequireAnExplicitRecoveryDecision() {
        GenerationOrchestrationTask task = runningTask();
        task.setRuntimeState(AgentRuntimeState.FAILED);
        task.setCurrentNode("review");
        task.getNodeStatuses().put("review", "failed");

        GenerationDagCheckpointRecoveryPolicy.Assessment assessment =
                GenerationDagCheckpointRecoveryPolicy.assess(task);

        assertFalse(assessment.automaticallyRecoverable());
        assertEquals(GenerationDagCheckpointRecoveryPolicy.Disposition.FAILED_REQUIRES_DECISION,
                assessment.disposition());
    }

    @Test
    void incompleteCompletedCheckpointMustBeRejected() {
        GenerationOrchestrationTask task = runningTask();
        task.setStatus("completed");
        task.setRuntimeState(AgentRuntimeState.COMPLETED);
        task.setTerminationReason("success");

        GenerationDagCheckpointRecoveryPolicy.Assessment assessment =
                GenerationDagCheckpointRecoveryPolicy.assess(task);

        assertFalse(assessment.automaticallyRecoverable());
        assertEquals(GenerationDagCheckpointRecoveryPolicy.Disposition.INVALID,
                assessment.disposition());
    }

    private GenerationOrchestrationTask runningTask() {
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-recovery-policy");
        task.setAppId(1L);
        task.setStatus("running");
        return task;
    }
}
