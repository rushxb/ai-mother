package com.rush.rushaicodemother.orchestration.planning;

import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.agent.CompactPlanningAgentNode;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 将当前逐节点图折叠到单检查点的消融适配器。 */
@Component
public final class CompactPlanningGraphAdapter implements GenerationPlanningGraphAdapter {

    private final CurrentDagPlanningGraphAdapter currentDag;

    public CompactPlanningGraphAdapter(CurrentDagPlanningGraphAdapter currentDag) {
        this.currentDag = Objects.requireNonNull(currentDag, "当前 DAG 适配器不能为空");
    }

    @Override
    public GenerationPlanningVariant variant() {
        return GenerationPlanningVariant.COMPACT_PLAN;
    }

    @Override
    public List<GenerationAgentNode> nodes(boolean heavyPath) {
        return List.of(new CompactPlanningAgentNode(currentDag.nodes(heavyPath)));
    }
}
