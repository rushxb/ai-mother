package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.GenerationWorkingMemoryProperties;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationWorkingMemoryServiceTest {

    @Test
    void workingMemoryMustTrackRouteStageContextAndCompletion() {
        GenerationWorkingMemoryService service = new GenerationWorkingMemoryService(
                new GenerationWorkingMemoryProperties());
        service.initialize("task-memory", 1L, 2L, "agent_edit");
        service.recordContextDigest("task-memory", "digest-1");
        service.recordEvent("task-memory", GenerationStreamEvent.agentEvent("", Map.of(
                "stage", "verify", "summary", "running tests")));
        service.complete("task-memory");

        GenerationWorkingMemorySnapshot snapshot = service.snapshot("task-memory").orElseThrow();
        assertEquals("agent_edit", snapshot.route());
        assertEquals("verify", snapshot.currentStage());
        assertEquals("digest-1", snapshot.contextDigest());
        assertTrue(snapshot.completed());
        assertEquals(1, snapshot.recentEvents().size());
    }
}
