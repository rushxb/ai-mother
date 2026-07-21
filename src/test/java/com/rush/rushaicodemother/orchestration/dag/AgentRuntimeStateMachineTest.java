package com.rush.rushaicodemother.orchestration.dag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRuntimeStateMachineTest {

    @Test
    void terminalWorkflowMustRejectFurtherActions() {
        AgentRuntimeStateMachine stateMachine = new AgentRuntimeStateMachine();
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        GenerationAgentNode node = node("review", "quality");

        stateMachine.startNode(task, node);
        assertEquals(AgentRuntimeState.VERIFYING, task.getRuntimeState());
        stateMachine.checkpointNode(task, node);
        stateMachine.complete(task);

        assertEquals(AgentRuntimeState.COMPLETED, task.getRuntimeState());
        assertEquals(2, task.getCheckpointVersion());
        assertThrows(IllegalStateException.class, () -> stateMachine.startNode(task, node));
    }

    private GenerationAgentNode node(String key, String stage) {
        return new GenerationAgentNode() {
            public String key() { return key; }
            public String agentName() { return key; }
            public String stage() { return stage; }
            public List<String> dependencies() { return List.of(); }
            public AgentNodeResult execute(GenerationAgentContext context) {
                return AgentNodeResult.of("done", List.of(), java.util.Map.of());
            }
        };
    }
}
