package com.rush.rushaicodemother.orchestration.runtime.model;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationModelCancellationScopeTest {

    @Test
    void cancellationMustReachExistingAndLateHandlesExactlyOnce() {
        GenerationModelCancellationScope scope = new GenerationModelCancellationScope();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger late = new AtomicInteger();

        scope.register(first::incrementAndGet);
        scope.cancel();
        scope.cancel();
        scope.register(late::incrementAndGet);

        assertTrue(scope.isCancelled());
        assertEquals(1, first.get());
        assertEquals(1, late.get());
    }

    @Test
    void completedScopeMustDiscardLateHandlesWithoutCancellingThem() {
        GenerationModelCancellationScope scope = new GenerationModelCancellationScope();
        AtomicInteger cancellations = new AtomicInteger();

        scope.complete();
        scope.register(cancellations::incrementAndGet);
        scope.cancel();

        assertFalse(scope.isCancelled());
        assertEquals(0, cancellations.get());
    }
}
