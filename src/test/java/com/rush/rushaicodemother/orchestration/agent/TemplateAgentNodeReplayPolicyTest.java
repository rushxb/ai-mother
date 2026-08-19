package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.orchestration.dag.GenerationNodeReplayPolicy;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class TemplateAgentNodeReplayPolicyTest {

    @Test
    void templatePublicationMustKeepADurableStartBoundary() {
        TemplateAgentNode node = new TemplateAgentNode(
                mock(GenerationTemplateBootstrapRegistry.class));

        assertEquals(GenerationNodeReplayPolicy.REQUIRES_START_CHECKPOINT, node.replayPolicy());
    }
}
