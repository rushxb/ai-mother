package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.orchestration.artifact.ArchitecturePlan;
import com.rush.rushaicodemother.orchestration.artifact.ContextSummaryArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationNodeReplayPolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Architect：模块划分与工程策略。
 */
@Component
public class ArchitectAgentNode extends BaseGenerationAgentNode {

    private final GenerationAgentSupport support;

    public ArchitectAgentNode(GenerationAgentSupport support) {
        super("architect", "Architect", "architecture", List.of("planner", "context"),
                GenerationNodeReplayPolicy.REPLAY_SAFE);
        this.support = support;
    }

    /**
 * 执行架构智能体节点处理流程。
 *
 * @param context 执行上下文
 * @return 架构智能体节点
 */
    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        String projectContext = context.getArtifact(ContextSummaryArtifact.KEY)
                .map(ContextSummaryArtifact::fromArtifact)
                .map(ContextSummaryArtifact::projectContext)
                .orElseThrow(() -> new IllegalStateException("缺少项目上下文制品，无法完成架构规划"));
        List<String> modules = support.inferModules(context.getRequest().userMessage(), projectContext);
        List<String> constraints = List.of(
                "优先复用已有目录和依赖",
                "模块内改动聚合，跨模块接口最小化",
                "为 Review 与 BuildFix 保留清晰的文件边界"
        );
        ArchitecturePlan architecturePlan = new ArchitecturePlan(
                modules, constraints, context.getTargetType(), modules.size() > 1);
        GenerationArtifact artifact = architecturePlan.toArtifact();
        return AgentNodeResult.of(
                architecturePlan.parallelizable()
                        ? "已完成模块划分，可并行生成"
                        : "已完成工程结构规划",
                List.of(artifact),
                Map.of("moduleCount", architecturePlan.modules().size(),
                        "parallelizable", architecturePlan.parallelizable())
        );
    }
}
