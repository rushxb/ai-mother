package com.rush.rushaicodemother.monitor.span;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationSpanObservationTest {

    private static final Instant NOW = Instant.parse("2026-07-16T04:00:00Z");

    @Test
    void shouldRejectInconsistentTimeRange() {
        assertThrows(IllegalArgumentException.class, () -> observation(
                NOW, NOW.minusMillis(1), 0, ""));
    }

    @Test
    void shouldRejectNegativeDurationAndOversizedDetail() {
        assertThrows(IllegalArgumentException.class, () -> observation(NOW, NOW, -1, ""));
        assertThrows(IllegalArgumentException.class, () -> observation(
                NOW, NOW, 0, "x".repeat(GenerationSpanObservation.MAX_DETAIL_LENGTH + 1)));
    }

    @Test
    void nullDetailShouldNormalizeToEmptyText() {
        GenerationSpanObservation observation = observation(NOW, NOW, 0, null);

        assertEquals("", observation.detail());
    }

    private GenerationSpanObservation observation(Instant startedAt,
                                                  Instant endedAt,
                                                  long durationMs,
                                                  String detail) {
        return new GenerationSpanObservation(
                "e106ce55-03ab-4fe2-a917-bcdd60bd2348",
                "task-1",
                "build",
                GenerationSpanCategory.BUILD,
                "success",
                startedAt,
                endedAt,
                durationMs,
                detail
        );
    }
}
