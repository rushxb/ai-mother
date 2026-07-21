package com.rush.rushaicodemother.infrastructure.telemetry;

import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MicrometerGenerationTraceContextBridgeTest {

    private static final String TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void captureAndWrapMustContinuePersistedW3cContext() {
        Tracer tracer = mock(Tracer.class);
        Span current = mock(Span.class);
        TraceContext currentContext = mock(TraceContext.class);
        Span.Builder extractedBuilder = mock(Span.Builder.class);
        Span workerSpan = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        RecordingPropagator propagator = new RecordingPropagator(extractedBuilder);
        when(tracer.currentSpan()).thenReturn(current);
        when(current.context()).thenReturn(currentContext);
        when(extractedBuilder.name(anyString())).thenReturn(extractedBuilder);
        when(extractedBuilder.kind(Span.Kind.CONSUMER)).thenReturn(extractedBuilder);
        when(extractedBuilder.start()).thenReturn(workerSpan);
        when(tracer.withSpan(workerSpan)).thenReturn(scope);
        MicrometerGenerationTraceContextBridge bridge =
                new MicrometerGenerationTraceContextBridge(tracer, propagator);

        GenerationTraceContext captured = bridge.capture();
        AtomicBoolean executed = new AtomicBoolean(false);
        bridge.wrap(captured, "generation.task.execute", Map.of("generation.route", "create"),
                () -> executed.set(true)).run();

        assertEquals(TRACEPARENT, captured.traceparent());
        assertEquals(TRACEPARENT, propagator.extractedTraceparent);
        assertTrue(executed.get());
        verify(workerSpan).tag("generation.route", "create");
        verify(workerSpan).end();
        verify(scope).close();
    }

    @Test
    void wrappedFailureMustBeRecordedAndRethrown() {
        Tracer tracer = mock(Tracer.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        when(tracer.spanBuilder()).thenReturn(builder);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.kind(Span.Kind.CONSUMER)).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(scope);
        MicrometerGenerationTraceContextBridge bridge =
                new MicrometerGenerationTraceContextBridge(tracer, Propagator.NOOP);
        IllegalStateException failure = new IllegalStateException("boom");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> bridge.wrap(GenerationTraceContext.empty(), "generation.task.execute", Map.of(),
                        () -> { throw failure; }).run());

        assertEquals(failure, thrown);
        verify(span).error(failure);
        verify(span).end();
    }

    private static final class RecordingPropagator implements Propagator {

        private final Span.Builder extractedBuilder;
        private String extractedTraceparent;

        private RecordingPropagator(Span.Builder extractedBuilder) {
            this.extractedBuilder = extractedBuilder;
        }

        @Override
        public List<String> fields() {
            return List.of("traceparent", "tracestate");
        }

        @Override
        public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {
            setter.set(carrier, "traceparent", TRACEPARENT);
            setter.set(carrier, "tracestate", "vendor=value");
        }

        @Override
        public <C> Span.Builder extract(C carrier, Getter<C> getter) {
            extractedTraceparent = getter.get(carrier, "traceparent");
            return extractedBuilder;
        }
    }
}
