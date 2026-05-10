package com.yupi.yuaicodemother.orchestration.agent;

import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentContext;
import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentNode;

import java.util.List;

/**
 * 角色节点基类。
 */
public abstract class BaseGenerationAgentNode implements GenerationAgentNode {

    private final String key;
    private final String agentName;
    private final String stage;
    private final List<String> dependencies;

    protected BaseGenerationAgentNode(String key, String agentName, String stage, List<String> dependencies) {
        this.key = key;
        this.agentName = agentName;
        this.stage = stage;
        this.dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
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
