package com.rush.rushaicodemother.orchestration.progress;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningProgressTrackerTest {

    @Test
    void mustPublishOnlyBoundedSummaryEvents() {
        ReasoningProgressTracker tracker = new ReasoningProgressTracker("task-1");

        GenerationStreamEvent started = tracker.startIfNeeded().orElseThrow();
        GenerationStreamEvent completed = tracker.completeIfStarted().orElseThrow();

        assertEquals(GenerationStreamEvent.AGENT_EVENT, started.getType());
        assertEquals("reasoning", started.getData().get("stage"));
        assertEquals("summary", started.getData().get("visibility"));
        assertEquals("running", started.getData().get("status"));
        assertEquals("done", completed.getData().get("status"));
        assertFalse(started.toString().contains("private"));
        assertTrue(tracker.startIfNeeded().isEmpty());
        assertTrue(tracker.completeIfStarted().isEmpty());
        assertTrue(tracker.failIfStarted().isEmpty());
    }

    @Test
    void mustNotPublishTerminalEventBeforeReasoningStarts() {
        ReasoningProgressTracker tracker = new ReasoningProgressTracker(null);

        assertTrue(tracker.completeIfStarted().isEmpty());
        assertTrue(tracker.failIfStarted().isEmpty());
    }
}
