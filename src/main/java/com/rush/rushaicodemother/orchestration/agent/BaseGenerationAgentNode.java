package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentNode;
import com.rush.rushaicodemother.orchestration.dag.GenerationNodeReplayPolicy;

import java.util.List;
import java.util.Set;

/**
 * 角色节点基类。
 */
public abstract class BaseGenerationAgentNode implements GenerationAgentNode {

    private final String key;
    private final String agentName;
    private final String stage;
    private final List<String> dependencies;
    private final GenerationNodeReplayPolicy replayPolicy;
    private final Set<String> requiredArtifactKeys;

    protected BaseGenerationAgentNode(String key,
                                      String agentName,
                                      String stage,
                                      List<String> dependencies,
                                      Set<String> requiredArtifactKeys) {
        this(
                key,
                agentName,
                stage,
                dependencies,
                GenerationNodeReplayPolicy.REQUIRES_START_CHECKPOINT,
                requiredArtifactKeys
        );
    }

    /**
     * 创建基础生成智能体节点实例并完成必要的依赖和初始状态设置。
     *
     * @param key 键
     * @param agentName 智能体名称
     * @param stage 阶段
     * @param dependencies 待处理的 {@code dependencies} 集合
     * @param replayPolicy {@code replayPolicy} 对应的调用参数
     * @param requiredArtifactKeys 节点完成时必须持久化的制品键
     */
    protected BaseGenerationAgentNode(String key,
                                      String agentName,
                                      String stage,
                                      List<String> dependencies,
                                      GenerationNodeReplayPolicy replayPolicy,
                                      Set<String> requiredArtifactKeys) {
        this.key = key;
        this.agentName = agentName;
        this.stage = stage;
        this.dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        this.replayPolicy = replayPolicy == null
                ? GenerationNodeReplayPolicy.REQUIRES_START_CHECKPOINT
                : replayPolicy;
        if (requiredArtifactKeys == null || requiredArtifactKeys.stream().anyMatch(
                artifactKey -> artifactKey == null || artifactKey.isBlank())) {
            throw new IllegalArgumentException("节点必需制品键不能为空");
        }
        this.requiredArtifactKeys = Set.copyOf(requiredArtifactKeys);
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

    @Override
    public Set<String> requiredArtifactKeys() {
        return requiredArtifactKeys;
    }

    /**
 * 返回制品{@code String}值。
 *
 * @param context 执行上下文
 * @param artifactKey 制品键
 * @param payloadKey 载荷键
 * @param defaultValue 默认值
 * @return 处理后的基础生成智能体节点文本
 */
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

    /**
 * 返回制品{@code Boolean}值。
 *
 * @param context 执行上下文
 * @param artifactKey 制品键
 * @param payloadKey 载荷键
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    protected boolean artifactBooleanValue(GenerationAgentContext context,
                                           String artifactKey,
                                           String payloadKey) {
        return Boolean.TRUE.equals(context.getArtifactValue(artifactKey, payloadKey));
    }
}
