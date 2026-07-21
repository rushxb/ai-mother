package com.rush.rushaicodemother.orchestration.eventstream;

import com.rush.rushaicodemother.config.GenerationEventStreamProperties;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalGenerationEventStreamTest {

    @Test
    void streamMustReplayEventsAndCompleteForLateSubscribers() {
        GenerationEventStreamProperties properties = new GenerationEventStreamProperties();
        LocalGenerationEventStream stream = new LocalGenerationEventStream(properties);

        stream.publish("task-local", GenerationStreamEvent.aiDelta("one"));
        stream.publish("task-local", GenerationStreamEvent.aiDelta("two"));
        stream.complete("task-local");

        List<GenerationStreamEvent> events = stream.stream("task-local")
                .collectList()
                .block(Duration.ofSeconds(1));

        assertTrue(stream.available("task-local"));
        assertEquals(List.of("one", "two"), events.stream()
                .map(GenerationStreamEvent::getText)
                .toList());
    }

    @Test
    void sequencedStreamMustResumeStrictlyAfterCursorAndRetainCompletionSequence() {
        LocalGenerationEventStream stream = new LocalGenerationEventStream(
                new GenerationEventStreamProperties());
        stream.publish("task-resume", GenerationStreamEvent.aiDelta("one"));
        stream.publish("task-resume", GenerationStreamEvent.aiDelta("two"));
        stream.complete("task-resume");

        List<SequencedGenerationEvent> events = stream.stream("task-resume", 1L)
                .collectList()
                .block(Duration.ofSeconds(1));

        assertEquals(2, events.size());
        assertEquals(2L, events.getFirst().sequence());
        assertEquals("two", events.getFirst().event().getText());
        assertEquals(3L, events.getLast().sequence());
        assertTrue(events.getLast().terminal());
    }

    @Test
    void replayTrimMustEmitExplicitGapBeforeFirstAvailableEvent() {
        GenerationEventStreamProperties properties = new GenerationEventStreamProperties();
        properties.setMaxEventsPerTask(3);
        LocalGenerationEventStream stream = new LocalGenerationEventStream(properties);
        stream.publish("task-gap", GenerationStreamEvent.aiDelta("one"));
        stream.publish("task-gap", GenerationStreamEvent.aiDelta("two"));
        stream.publish("task-gap", GenerationStreamEvent.aiDelta("three"));
        stream.publish("task-gap", GenerationStreamEvent.aiDelta("four"));
        stream.complete("task-gap");

        List<SequencedGenerationEvent> events = stream.stream("task-gap", 0L)
                .collectList()
                .block(Duration.ofSeconds(1));

        assertEquals(4, events.size());
        assertEquals(SequencedGenerationEvent.Kind.GAP, events.getFirst().kind());
        assertEquals(2L, events.getFirst().sequence());
        assertEquals(0L, events.getFirst().gap().requestedSeq());
        assertEquals(3L, events.getFirst().gap().firstAvailableSeq());
        assertEquals(List.of(2L, 3L, 4L, 5L), events.stream()
                .map(SequencedGenerationEvent::sequence)
                .toList());
        assertTrue(events.getLast().terminal());
    }

    @Test
    void invalidTaskIdentityMustNotCreateUnboundedStreamEntries() {
        LocalGenerationEventStream stream = new LocalGenerationEventStream(
                new GenerationEventStreamProperties());

        stream.publish("../escape", GenerationStreamEvent.aiDelta("ignored"));

        assertFalse(stream.available("../escape"));
        assertTrue(stream.stream("../escape").collectList().block().isEmpty());
    }

    @Test
    void replayBufferMustNeverRetainRawToolPayloads() {
        String secret = "replay-secret";
        LocalGenerationEventStream stream = new LocalGenerationEventStream(
                new GenerationEventStreamProperties());

        stream.publish("task-safe-replay", GenerationStreamEvent.toolCall("raw", Map.of(
                "toolName", "writeFile",
                "filePath", "src/App.vue",
                "arguments", "{\"password\":\"" + secret + "\"}",
                "content", "const password = \"" + secret + "\";"
        )));
        stream.complete("task-safe-replay");

        GenerationStreamEvent replayed = stream.stream("task-safe-replay")
                .blockFirst(Duration.ofSeconds(1));

        assertFalse(replayed.getData().containsKey("arguments"));
        assertFalse(String.valueOf(replayed).contains(secret));
        assertTrue(String.valueOf(replayed.getData().get("content")).contains("[REDACTED]"));
    }
}
