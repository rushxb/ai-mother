package com.rush.rushaicodemother.infrastructure.telemetry;

import com.rush.rushaicodemother.monitor.span.GenerationSpanObservation;
import com.rush.rushaicodemother.monitor.span.GenerationSpanSink;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 将现有一代关键路径观察结果导出为子 OTel 跨度。 */
@Component
@ConditionalOnProperty(name = "management.tracing.enabled", havingValue = "true", matchIfMissing = true)
public class MicrometerGenerationSpanSink implements GenerationSpanSink {

    private final Tracer tracer;
    private final GenerationTraceParentRegistry parentRegistry;

    @Autowired
    public MicrometerGenerationSpanSink(Tracer tracer,
                                        GenerationTraceParentRegistry parentRegistry) {
        this.tracer = tracer;
        this.parentRegistry = parentRegistry;
    }

    MicrometerGenerationSpanSink(Tracer tracer) {
        this(tracer, new GenerationTraceParentRegistry());
    }

    @Override
    public void record(GenerationSpanObservation observation) {
        if (observation == null) {
            return;
        }
        Optional<io.micrometer.tracing.TraceContext> parent = parentRegistry.find(observation.taskId());
        if (parent.isEmpty() && tracer.currentSpan() != null) {
            parent = Optional.ofNullable(tracer.currentSpan().context());
        }
        if (parent.isEmpty()) {
            return;
        }
        Span span = tracer.spanBuilder()
                .setParent(parent.orElseThrow())
                .name(spanName(observation))
                .startTimestamp(observation.startedAt().toEpochMilli(), TimeUnit.MILLISECONDS)
                .tag("generation.task.id", observation.taskId())
                .tag("generation.stage", observation.stage())
                .tag("generation.category", observation.category().name().toLowerCase(Locale.ROOT))
                .tag("generation.status", observation.status())
                .start();
        if (!isSuccess(observation.status())) {
            span.event("generation.operation.failed");
        }
        span.end(observation.endedAt().toEpochMilli(), TimeUnit.MILLISECONDS);
    }

    private String spanName(GenerationSpanObservation observation) {
        String stage = observation.stage()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_");
        String value = "generation."
                + observation.category().name().toLowerCase(Locale.ROOT)
                + "." + stage;
        return value.length() <= 96 ? value : value.substring(0, 96);
    }

    private boolean isSuccess(String status) {
        return status != null && switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "success", "succeeded", "passed", "completed", "ok" -> true;
            default -> false;
        };
    }
}
