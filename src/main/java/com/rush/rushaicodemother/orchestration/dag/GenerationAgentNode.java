package com.rush.rushaicodemother.orchestration.dag;

import java.util.List;

/**
 * 一个可被 DAG 调度的智能体节点。
 */
public interface GenerationAgentNode {

    String key();

    String agentName();

    String stage();

    List<String> dependencies();

    default GenerationNodeReplayPolicy replayPolicy() {
        return GenerationNodeReplayPolicy.REQUIRES_START_CHECKPOINT;
    }

    AgentNodeResult execute(GenerationAgentContext context);
}
