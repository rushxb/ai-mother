package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.orchestration.dag.GenerationNodeReplayPolicy;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.agent.template.GenerationTemplateBootstrapAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateAgentNodeReplayPolicyTest {

    @Test
    void templatePublicationMustKeepADurableStartBoundary() {
        GenerationTemplateBootstrapAdapter adapter =
                mock(GenerationTemplateBootstrapAdapter.class);
        when(adapter.codeGenType()).thenReturn(CodeGenTypeEnum.VUE_PROJECT);
        TemplateAgentNode node = new TemplateAgentNode(List.of(adapter));

        assertEquals(GenerationNodeReplayPolicy.REQUIRES_START_CHECKPOINT, node.replayPolicy());
    }
}
