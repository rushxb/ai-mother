package com.rush.rushaicodemother.orchestration.planning;

import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.agent.ContextAgentNode;
import com.rush.rushaicodemother.orchestration.agent.NoPlanningAgentNode;
import com.rush.rushaicodemother.orchestration.agent.TemplateAgentNode;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 仅保留模板、上下文和运行必需规范的无规划基线适配器。 */
@Component
public final class NoPlanningGraphAdapter implements GenerationPlanningGraphAdapter {

    private final TemplateAgentNode template;
    private final ContextAgentNode context;

    public NoPlanningGraphAdapter(TemplateAgentNode template,
                                  ContextAgentNode context) {
        this.template = Objects.requireNonNull(template, "模板节点不能为空");
        this.context = Objects.requireNonNull(context, "上下文节点不能为空");
    }

    @Override
    public GenerationPlanningVariant variant() {
        return GenerationPlanningVariant.NO_PLAN;
    }

    @Override
    public List<GenerationAgentNode> nodes(boolean heavyPath) {
        return List.of(new NoPlanningAgentNode(template, context));
    }
}
