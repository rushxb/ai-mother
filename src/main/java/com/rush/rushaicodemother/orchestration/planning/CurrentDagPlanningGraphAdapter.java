package com.rush.rushaicodemother.orchestration.planning;

import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.agent.ArchitectAgentNode;
import com.rush.rushaicodemother.orchestration.agent.BuildFixAgentNode;
import com.rush.rushaicodemother.orchestration.agent.CodeAgentNode;
import com.rush.rushaicodemother.orchestration.agent.ContextAgentNode;
import com.rush.rushaicodemother.orchestration.agent.PlannerAgentNode;
import com.rush.rushaicodemother.orchestration.agent.ReviewAgentNode;
import com.rush.rushaicodemother.orchestration.agent.TemplateAgentNode;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 保持当前逐节点 DAG 行为的生产基线适配器。 */
@Component
public final class CurrentDagPlanningGraphAdapter implements GenerationPlanningGraphAdapter {

    private final List<GenerationAgentNode> lightNodes;
    private final List<GenerationAgentNode> heavyNodes;

    public CurrentDagPlanningGraphAdapter(PlannerAgentNode planner,
                                          TemplateAgentNode template,
                                          ContextAgentNode context,
                                          ArchitectAgentNode architect,
                                          CodeAgentNode code,
                                          ReviewAgentNode review,
                                          BuildFixAgentNode buildFix) {
        this.lightNodes = List.of(planner, template, context, architect, code, review);
        List<GenerationAgentNode> heavy = new ArrayList<>(lightNodes);
        heavy.add(buildFix);
        this.heavyNodes = List.copyOf(heavy);
    }

    @Override
    public GenerationPlanningVariant variant() {
        return GenerationPlanningVariant.CURRENT_DAG;
    }

    @Override
    public List<GenerationAgentNode> nodes(boolean heavyPath) {
        return heavyPath ? heavyNodes : lightNodes;
    }
}
