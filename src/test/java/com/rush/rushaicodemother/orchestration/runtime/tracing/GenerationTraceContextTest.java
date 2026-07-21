package com.rush.rushaicodemother.orchestration.runtime.tracing;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationTraceContextTest {

    private static final String TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void carrierMustRetainOnlyW3cTraceIdentity() {
        GenerationTraceContext context = GenerationTraceContext.fromCarrier(Map.of(
                "TraceParent", TRACEPARENT,
                "tracestate", "vendor=value",
                "baggage", "must-not-be-persisted"
        ));

        assertEquals(TRACEPARENT, context.traceparent());
        assertEquals("vendor=value", context.tracestate());
        assertEquals(Map.of(
                "traceparent", TRACEPARENT,
                "tracestate", "vendor=value"
        ), context.carrier());
    }

    @Test
    void missingTraceparentMustDiscardOrphanTracestate() {
        GenerationTraceContext context = new GenerationTraceContext(null, "vendor=value");

        assertTrue(context.isEmpty());
        assertNull(context.tracestate());
    }

    @Test
    void malformedTraceIdentityMustBeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationTraceContext("00-invalid", null));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationTraceContext(
                        "00-00000000000000000000000000000000-00f067aa0ba902b7-01", null));
    }
}
