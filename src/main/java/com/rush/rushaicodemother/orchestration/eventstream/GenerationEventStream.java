package com.rush.rushaicodemother.orchestration.eventstream;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import reactor.core.publisher.Flux;

/** Cross-instance transport port for task-scoped generation events. */
public interface GenerationEventStream {

    void publish(String taskId, GenerationStreamEvent event);

    void complete(String taskId);

    boolean available(String taskId);

    /** Legacy projection retained for app-scoped compatibility endpoints. */
    default Flux<GenerationStreamEvent> stream(String taskId) {
        return stream(taskId, 0L)
                .filter(SequencedGenerationEvent::domainEvent)
                .map(SequencedGenerationEvent::event);
    }

    /** Replays entries strictly after the supplied sequence and then tails live events. */
    Flux<SequencedGenerationEvent> stream(String taskId, long afterSequence);
}