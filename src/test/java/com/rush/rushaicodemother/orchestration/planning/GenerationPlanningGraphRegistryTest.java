package com.rush.rushaicodemother.orchestration.planning;

import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class GenerationPlanningGraphRegistryTest {

    @Test
    void resolveMustDelegateToTheAdapterRegisteredForTheRequestedVariant() {
        GenerationAgentNode currentNode = mock(GenerationAgentNode.class);
        GenerationAgentNode compactNode = mock(GenerationAgentNode.class);
        GenerationAgentNode noPlanNode = mock(GenerationAgentNode.class);
        GenerationPlanningGraphRegistry registry = new GenerationPlanningGraphRegistry(List.of(
                adapter(GenerationPlanningVariant.CURRENT_DAG, currentNode),
                adapter(GenerationPlanningVariant.COMPACT_PLAN, compactNode),
                adapter(GenerationPlanningVariant.NO_PLAN, noPlanNode)
        ));

        assertEquals(List.of(currentNode), registry.resolve(
                GenerationPlanningVariant.CURRENT_DAG, false));
        assertEquals(List.of(compactNode), registry.resolve(
                GenerationPlanningVariant.COMPACT_PLAN, true));
        assertEquals(List.of(noPlanNode), registry.resolve(
                GenerationPlanningVariant.NO_PLAN, false));
    }

    @Test
    void duplicateVariantRegistrationMustFailAtStartup() {
        GenerationAgentNode node = mock(GenerationAgentNode.class);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new GenerationPlanningGraphRegistry(List.of(
                        adapter(GenerationPlanningVariant.CURRENT_DAG, node),
                        adapter(GenerationPlanningVariant.CURRENT_DAG, node)
                ))
        );

        assertTrue(exception.getMessage().contains("CURRENT_DAG"));
    }

    @Test
    void everyPlanningVariantMustHaveAnAdapterAtStartup() {
        GenerationAgentNode node = mock(GenerationAgentNode.class);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new GenerationPlanningGraphRegistry(List.of(
                        adapter(GenerationPlanningVariant.CURRENT_DAG, node),
                        adapter(GenerationPlanningVariant.COMPACT_PLAN, node)
                ))
        );

        assertTrue(exception.getMessage().contains("NO_PLAN"));
    }

    private GenerationPlanningGraphAdapter adapter(
            GenerationPlanningVariant variant,
            GenerationAgentNode node
    ) {
        return new GenerationPlanningGraphAdapter() {
            @Override
            public GenerationPlanningVariant variant() {
                return variant;
            }

            @Override
            public List<GenerationAgentNode> nodes(boolean heavyPath) {
                return List.of(node);
            }
        };
    }
}
