package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.orchestration.dag.GenerationNodeReplayPolicy;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackPortAllocator;
import com.rush.rushaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class TemplateAgentNodeReplayPolicyTest {

    @Test
    void templatePublicationMustKeepADurableStartBoundary() {
        TemplateAgentNode node = new TemplateAgentNode(
                mock(VueProjectTemplateBootstrapService.class),
                mock(BackendProjectTemplateBootstrapService.class),
                mock(FullStackPortAllocator.class)
        );

        assertEquals(GenerationNodeReplayPolicy.REQUIRES_START_CHECKPOINT, node.replayPolicy());
    }
}
