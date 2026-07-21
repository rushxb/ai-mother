package com.rush.rushaicodemother.infrastructure.telemetry;

import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.monitor.span.GenerationSpanObservation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MicrometerGenerationTracePropagationIntegrationTest {

    @Test
    void persistedCarrierMustContinueTheOriginalTraceOnAnotherWorkerScope() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        ContextPropagators propagators = ContextPropagators.create(
                W3CTraceContextPropagator.getInstance());
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(propagators)
                .build();
        io.opentelemetry.api.trace.Tracer otelTracer = openTelemetry.getTracer("generation-trace-test");
        Tracer tracer = new OtelTracer(
                otelTracer,
                new OtelCurrentTraceContext(),
                ignored -> { }
        );
        GenerationTraceParentRegistry parentRegistry = new GenerationTraceParentRegistry();
        MicrometerGenerationTraceContextBridge bridge =
                new MicrometerGenerationTraceContextBridge(
                        tracer,
                        new OtelPropagator(propagators, otelTracer),
                        parentRegistry
                );
        MicrometerGenerationSpanSink spanSink =
                new MicrometerGenerationSpanSink(tracer, parentRegistry);

        Span requestSpan = tracer.nextSpan().name("http.request").start();
        GenerationTraceContext carrier;
        try (Tracer.SpanInScope ignored = tracer.withSpan(requestSpan)) {
            carrier = bridge.capture();
        } finally {
            requestSpan.end();
        }
        Instant childStartedAt = Instant.now();
        bridge.wrap(
                carrier,
                "generation.task.execute",
                Map.of(
                        "generation.task.id", "task-trace",
                        "generation.route", "create"
                ),
                () -> {
                    Thread callback = Thread.ofPlatform().start(() -> spanSink.record(
                            new GenerationSpanObservation(
                                    "domain-span", "task-trace", "llm_generation",
                                    GenerationSpanCategory.MODEL, "success",
                                    childStartedAt, Instant.now(), 1, ""
                            )
                    ));
                    try {
                        callback.join();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
        ).run();
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData request = find(spans, "http.request");
        SpanData worker = find(spans, "generation.task.execute");
        SpanData model = find(spans, "generation.model.llm_generation");
        assertFalse(carrier.isEmpty());
        assertEquals(request.getTraceId(), worker.getTraceId());
        assertEquals(request.getSpanId(), worker.getParentSpanId());
        assertEquals(worker.getSpanId(), model.getParentSpanId());
        assertEquals(SpanKind.CONSUMER, worker.getKind());
        assertEquals(StatusCode.UNSET, worker.getStatus().getStatusCode());

        tracerProvider.close();
    }

    private SpanData find(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow();
    }
}
