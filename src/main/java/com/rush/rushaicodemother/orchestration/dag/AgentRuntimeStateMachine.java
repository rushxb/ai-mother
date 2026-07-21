package com.rush.rushaicodemother.orchestration.dag;

/** Domain state machine that owns workflow transitions and versioned checkpoint metadata. */
public final class AgentRuntimeStateMachine {

    public synchronized void startNode(GenerationOrchestrationTask task, GenerationAgentNode node) {
        requireTask(task);
        if (task.getRuntimeState().terminal()) {
            throw new IllegalStateException("terminal agent workflow cannot start another node");
        }
        task.setRuntimeState(stateFor(node));
        task.setCurrentNode(node.key());
        task.setTerminationReason(null);
    }

    public synchronized void checkpointNode(GenerationOrchestrationTask task, GenerationAgentNode node) {
        requireTask(task);
        if (!node.key().equals(task.getCurrentNode())) {
            throw new IllegalStateException("agent checkpoint does not match the active node");
        }
        task.setLastCompletedNode(node.key());
        task.setCurrentNode(null);
        task.setCheckpointVersion(task.getCheckpointVersion() + 1);
    }

    public synchronized void complete(GenerationOrchestrationTask task) {
        requireTask(task);
        if (task.getRuntimeState() == AgentRuntimeState.FAILED) {
            throw new IllegalStateException("failed agent workflow cannot complete successfully");
        }
        task.setRuntimeState(AgentRuntimeState.COMPLETED);
        task.setCurrentNode(null);
        task.setTerminationReason("success");
        task.setCheckpointVersion(task.getCheckpointVersion() + 1);
    }

    public synchronized void fail(GenerationOrchestrationTask task, String reason) {
        requireTask(task);
        if (task.getRuntimeState() == AgentRuntimeState.COMPLETED) {
            return;
        }
        task.setRuntimeState(AgentRuntimeState.FAILED);
        task.setCurrentNode(null);
        task.setTerminationReason(reason == null || reason.isBlank() ? "unknown" : reason.trim());
        task.setCheckpointVersion(task.getCheckpointVersion() + 1);
    }

    private AgentRuntimeState stateFor(GenerationAgentNode node) {
        String stage = node == null || node.stage() == null ? "" : node.stage().toLowerCase();
        if (stage.contains("quality") || stage.contains("verify") || stage.contains("review")) {
            return AgentRuntimeState.VERIFYING;
        }
        if (stage.contains("repair") || stage.contains("buildfix")) {
            return AgentRuntimeState.REPAIRING;
        }
        return AgentRuntimeState.RUNNING;
    }

    private void requireTask(GenerationOrchestrationTask task) {
        if (task == null) {
            throw new IllegalArgumentException("generation orchestration task cannot be null");
        }
        if (task.getRuntimeState() == null) {
            task.setRuntimeState(AgentRuntimeState.INITIALIZED);
        }
    }
}
