package com.rush.rushaicodemother.orchestration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationSessionTest {

    @Test
    void cancelInvokesRegisteredHandleOnlyOnce() {
        GenerationSession session = new GenerationSession(null);
        AtomicInteger cancellations = new AtomicInteger();

        session.setCancellationHandle(cancellations::incrementAndGet);
        session.cancel();
        session.cancel();

        assertEquals(1, cancellations.get());
    }

    @Test
    void registeringHandleAfterCancellationCancelsItImmediately() {
        GenerationSession session = new GenerationSession(null);
        AtomicInteger cancellations = new AtomicInteger();

        session.cancel();
        session.setCancellationHandle(cancellations::incrementAndGet);

        assertEquals(1, cancellations.get());
    }

    @Test
    void cancellationTargetsMostRecentlyRegisteredHandle() {
        GenerationSession session = new GenerationSession(null);
        AtomicInteger previousCancellations = new AtomicInteger();
        AtomicInteger currentCancellations = new AtomicInteger();

        session.setCancellationHandle(previousCancellations::incrementAndGet);
        session.setCancellationHandle(currentCancellations::incrementAndGet);
        session.cancel();

        assertEquals(0, previousCancellations.get());
        assertEquals(1, currentCancellations.get());
    }
}
