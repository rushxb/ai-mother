package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentNode;
import com.rush.rushaicodemother.orchestration.dag.GenerationNodeReplayPolicy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 在一个持久检查点内复用现有确定性规划步骤。 */
public class CompactPlanningAgentNode extends BaseGenerationAgentNode {

    private final List<GenerationAgentNode> delegates;

    public CompactPlanningAgentNode(List<GenerationAgentNode> delegates) {
        super(
                "compact_plan",
                "CompactPlan",
                "planning",
                List.of(),
                GenerationNodeReplayPolicy.REQUIRES_START_CHECKPOINT,
                requiredArtifactKeys(delegates)
        );
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        List<GenerationArtifact> artifacts = new ArrayList<>();
        for (GenerationAgentNode delegate : delegates) {
            AgentNodeResult result = delegate.execute(context);
            context.putArtifacts(result.artifacts());
            artifacts.addAll(result.artifacts());
        }
        return AgentNodeResult.of(
                "精简规划已在单检查点内完成",
                artifacts,
                Map.of("mergedStageCount", delegates.size())
        );
    }

    /** 折叠节点必须承诺其全部委托节点的完成制品，避免单检查点变体降低恢复强度。 */
    private static Set<String> requiredArtifactKeys(List<GenerationAgentNode> delegates) {
        Objects.requireNonNull(delegates, "精简规划委托节点不能为空");
        Set<String> artifactKeys = new LinkedHashSet<>();
        for (GenerationAgentNode delegate : delegates) {
            if (delegate == null) {
                throw new IllegalArgumentException("精简规划委托节点不能包含空值");
            }
            artifactKeys.addAll(delegate.requiredArtifactKeys());
        }
        return Set.copyOf(artifactKeys);
    }
}
