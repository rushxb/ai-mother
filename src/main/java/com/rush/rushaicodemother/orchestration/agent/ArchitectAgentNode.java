package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Architect：模块划分与工程策略。
 */
@Component
public class ArchitectAgentNode extends BaseGenerationAgentNode {

    private final GenerationAgentSupport support;

    public ArchitectAgentNode(GenerationAgentSupport support) {
        super("architect", "Architect", "architecture", List.of("planner", "context"));
        this.support = support;
    }

    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        String projectContext = artifactStringValue(context, "context_summary", "projectContext", "");
        List<String> modules = support.inferModules(context.getRequest().userMessage(), projectContext);
        List<String> constraints = List.of(
                "优先复用已有目录和依赖",
                "模块内改动聚合，跨模块接口最小化",
                "为 Review 与 BuildFix 保留清晰的文件边界"
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modules", modules);
        payload.put("constraints", constraints);
        payload.put("targetType", context.getTargetType().getValue());
        payload.put("parallelizable", modules.size() > 1);
        GenerationArtifact artifact = GenerationArtifact.of("architecture_plan", "Architect", "架构规划", payload);
        return AgentNodeResult.of(
                modules.size() > 1 ? "已完成模块划分，可并行生成" : "已完成工程结构规划",
                List.of(artifact),
                Map.of("moduleCount", modules.size(), "parallelizable", modules.size() > 1)
        );
    }
}
