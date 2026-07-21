package com.rush.rushaicodemother.orchestration.eventstream;

import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicLong;

/** Applies a client cursor and turns every missing sequence range into an explicit recovery event. */
final class GenerationEventReplayCursor {

    private GenerationEventReplayCursor() {
    }

    static Flux<SequencedGenerationEvent> after(Flux<SequencedGenerationEvent> source,
                                                long afterSequence) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("generation event cursor cannot be negative");
        }
        return Flux.defer(() -> resume(source, new AtomicLong(afterSequence)));
    }

    static Flux<SequencedGenerationEvent> resume(Flux<SequencedGenerationEvent> source,
                                                 AtomicLong cursor) {
        if (source == null || cursor == null || cursor.get() < 0) {
            throw new IllegalArgumentException("generation event replay cursor is invalid");
        }
        return source.concatMap(entry -> {
            long current = cursor.get();
            long sequence = entry.sequence();
            if (sequence <= current) {
                return Flux.empty();
            }
            cursor.set(sequence);
            if (current < sequence - 1) {
                return Flux.just(
                        SequencedGenerationEvent.gap(sequence - 1, current, sequence),
                        entry
                );
            }
            return Flux.just(entry);
        });
    }
}