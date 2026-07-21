package com.rush.rushaicodemother.infrastructure.telemetry;

import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.monitor.span.GenerationSpanObservation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MicrometerGenerationSpanSinkTest {

    @Test
    void completedGenerationObservationMustBecomeChildTraceSpan() {
        Tracer tracer = mock(Tracer.class);
        Span current = mock(Span.class);
        TraceContext currentContext = mock(TraceContext.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        when(tracer.currentSpan()).thenReturn(current);
        when(current.context()).thenReturn(currentContext);
        when(tracer.spanBuilder()).thenReturn(builder);
        when(builder.setParent(currentContext)).thenReturn(builder);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.startTimestamp(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(builder);
        when(builder.tag(anyString(), anyString())).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        MicrometerGenerationSpanSink sink = new MicrometerGenerationSpanSink(tracer);
        Instant startedAt = Instant.parse("2026-07-17T10:00:00Z");
        Instant endedAt = startedAt.plusMillis(1250);

        sink.record(new GenerationSpanObservation(
                "span-1", "task-1", "llm_generation", GenerationSpanCategory.MODEL,
                "success", startedAt, endedAt, 1250, ""
        ));

        verify(builder).name("generation.model.llm_generation");
        verify(builder).startTimestamp(startedAt.toEpochMilli(), TimeUnit.MILLISECONDS);
        verify(builder).tag("generation.task.id", "task-1");
        verify(span).end(endedAt.toEpochMilli(), TimeUnit.MILLISECONDS);
    }
}
