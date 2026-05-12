package com.yupi.yuaicodemother.core.handler;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GenerationStreamEventTest {

    @Test
    void generationStageShouldUseDedicatedEventType() {
        GenerationStreamEvent event = GenerationStreamEvent.generationStage("代码生成完成", Map.of(
                "stage", "codegen_done",
                "status", "transition"
        ));

        assertEquals(GenerationStreamEvent.GENERATION_STAGE, event.getType());
        assertFalse(GenerationStreamEvent.BUILD_RESULT.equals(event.getType()));
        assertEquals("codegen_done", event.getData().get("stage"));
        assertEquals("transition", event.getData().get("status"));
    }
}
