package com.rush.rushaicodemother.orchestration.dag;

import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;

import java.util.List;
import java.util.Map;

/**
 * 智能体节点执行结果。
 */
public record AgentNodeResult(
        String summary,
        List<GenerationArtifact> artifacts,
        Map<String, Object> data
) {

    /**
 * 根据给定参数创建当前对象。
 *
 * @param summary 汇总
 * @param artifacts 待处理的 {@code artifacts} 集合
 * @param data {@code data} 对应的调用参数
 * @return 智能体节点结果
 */
    public static AgentNodeResult of(String summary, List<GenerationArtifact> artifacts, Map<String, Object> data) {
        return new AgentNodeResult(
                summary,
                artifacts == null ? List.of() : List.copyOf(artifacts),
                data == null ? Map.of() : Map.copyOf(data)
        );
    }
}
