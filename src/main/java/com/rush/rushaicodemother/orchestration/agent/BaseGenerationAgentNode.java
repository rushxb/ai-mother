package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentNode;
import com.rush.rushaicodemother.orchestration.dag.GenerationNodeReplayPolicy;

import java.util.List;

/**
 * 角色节点基类。
 */
public abstract class BaseGenerationAgentNode implements GenerationAgentNode {

    private final String key;
    private final String agentName;
    private final String stage;
    private final List<String> dependencies;
    private final GenerationNodeReplayPolicy replayPolicy;

    protected BaseGenerationAgentNode(String key, String agentName, String stage, List<String> dependencies) {
        this(key, agentName, stage, dependencies, GenerationNodeReplayPolicy.REQUIRES_START_CHECKPOINT);
    }

    protected BaseGenerationAgentNode(String key,
                                      String agentName,
                                      String stage,
                                      List<String> dependencies,
                                      GenerationNodeReplayPolicy replayPolicy) {
        this.key = key;
        this.agentName = agentName;
        this.stage = stage;
        this.dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        this.replayPolicy = replayPolicy == null
                ? GenerationNodeReplayPolicy.REQUIRES_START_CHECKPOINT
                : replayPolicy;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public String agentName() {
        return agentName;
    }

    @Override
    public String stage() {
        return stage;
    }

    @Override
    public List<String> dependencies() {
        return dependencies;
    }

    @Override
    public GenerationNodeReplayPolicy replayPolicy() {
        return replayPolicy;
    }

    protected String artifactStringValue(GenerationAgentContext context,
                                         String artifactKey,
                                         String payloadKey,
                                         String defaultValue) {
        Object value = context.getArtifactValue(artifactKey, payloadKey);
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return String.valueOf(value);
    }

    protected boolean artifactBooleanValue(GenerationAgentContext context,
                                           String artifactKey,
                                           String payloadKey) {
        return Boolean.TRUE.equals(context.getArtifactValue(artifactKey, payloadKey));
    }
}
