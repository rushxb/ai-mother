package com.rush.rushaicodemother.orchestration.dag;

import java.util.List;
import java.util.Set;

/**
 * 一个可被 DAG 调度的智能体节点。
 */
public interface GenerationAgentNode {

    String key();

    String agentName();

    String stage();

    List<String> dependencies();

    /**
     * 声明节点进入完成态时必须持久化的制品键。
     *
     * <p>无制品节点可沿用空集合；有制品的生产节点应通过基类构造参数显式声明，
     * 由 DAG Runner 在新执行与检查点恢复两个方向统一校验。</p>
     */
    default Set<String> requiredArtifactKeys() {
        return Set.of();
    }

    default GenerationNodeReplayPolicy replayPolicy() {
        return GenerationNodeReplayPolicy.REQUIRES_START_CHECKPOINT;
    }

    AgentNodeResult execute(GenerationAgentContext context);
}
