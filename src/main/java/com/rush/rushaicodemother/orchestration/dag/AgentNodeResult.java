package com.rush.rushaicodemother.orchestration.dag;

import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;

import java.util.List;
import java.util.Map;

/**
 * DAG 节点执行结果。
 */
public record AgentNodeResult(
        String summary,
        List<GenerationArtifact> artifacts,
        Map<String, Object> data
) {

    public static AgentNodeResult of(String summary, List<GenerationArtifact> artifacts, Map<String, Object> data) {
        return new AgentNodeResult(
                summary,
                artifacts == null ? List.of() : List.copyOf(artifacts),
                data == null ? Map.of() : Map.copyOf(data)
        );
    }
}
