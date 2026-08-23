package com.rush.rushaicodemother.orchestration.planning;

import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.agent.ArchitectAgentNode;
import com.rush.rushaicodemother.orchestration.agent.BuildFixAgentNode;
import com.rush.rushaicodemother.orchestration.agent.CodeAgentNode;
import com.rush.rushaicodemother.orchestration.agent.ContextAgentNode;
import com.rush.rushaicodemother.orchestration.agent.GenerationRoutingSupport;
import com.rush.rushaicodemother.orchestration.agent.PlannerAgentNode;
import com.rush.rushaicodemother.orchestration.agent.ReviewAgentNode;
import com.rush.rushaicodemother.orchestration.agent.TemplateAgentNode;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationPlanningGraphAdapterTest {

    @Test
    void currentDagMustAppendBuildFixOnlyForHeavyExecution() {
        PlannerAgentNode planner = mock(PlannerAgentNode.class);
        TemplateAgentNode template = mock(TemplateAgentNode.class);
        ContextAgentNode context = mock(ContextAgentNode.class);
        ArchitectAgentNode architect = mock(ArchitectAgentNode.class);
        CodeAgentNode code = mock(CodeAgentNode.class);
        ReviewAgentNode review = mock(ReviewAgentNode.class);
        BuildFixAgentNode buildFix = mock(BuildFixAgentNode.class);
        CurrentDagPlanningGraphAdapter adapter = new CurrentDagPlanningGraphAdapter(
                planner, template, context, architect, code, review, buildFix);

        assertEquals(GenerationPlanningVariant.CURRENT_DAG, adapter.variant());
        assertEquals(
                List.of(planner, template, context, architect, code, review),
                adapter.nodes(false)
        );
        assertEquals(
                List.of(planner, template, context, architect, code, review, buildFix),
                adapter.nodes(true)
        );
    }

    @Test
    void compactPlanMustWrapTheCurrentDagForTheSameExecutionWeight() {
        CurrentDagPlanningGraphAdapter currentDag = mock(CurrentDagPlanningGraphAdapter.class);
        when(currentDag.nodes(true)).thenReturn(List.of(mock(GenerationAgentNode.class)));
        CompactPlanningGraphAdapter adapter = new CompactPlanningGraphAdapter(currentDag);

        List<GenerationAgentNode> nodes = adapter.nodes(true);

        assertEquals(GenerationPlanningVariant.COMPACT_PLAN, adapter.variant());
        assertEquals(List.of("compact_plan"), nodes.stream()
                .map(GenerationAgentNode::key)
                .toList());
        verify(currentDag).nodes(true);
    }

    @Test
    void noPlanMustExposeOnlyTheMinimalSingleCheckpoint() {
        NoPlanningGraphAdapter adapter = new NoPlanningGraphAdapter(
                mock(TemplateAgentNode.class),
                mock(ContextAgentNode.class),
                mock(GenerationRoutingSupport.class)
        );

        List<GenerationAgentNode> nodes = adapter.nodes(true);

        assertEquals(GenerationPlanningVariant.NO_PLAN, adapter.variant());
        assertEquals(List.of("no_plan"), nodes.stream()
                .map(GenerationAgentNode::key)
                .toList());
    }
}
