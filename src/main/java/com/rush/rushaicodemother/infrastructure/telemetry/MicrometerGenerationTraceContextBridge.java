package com.rush.rushaicodemother.infrastructure.telemetry;

import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContextBridge;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Micrometer/OTel adapter for W3C trace propagation through durable generation commands. */
@Component
@ConditionalOnProperty(name = "management.tracing.enabled", havingValue = "true", matchIfMissing = true)
public class MicrometerGenerationTraceContextBridge implements GenerationTraceContextBridge {

    private static final int MAX_TAGS = 16;
    private static final int MAX_TAG_VALUE_LENGTH = 256;

    private final Tracer tracer;
    private final Propagator propagator;
    private final GenerationTraceParentRegistry parentRegistry;

    public MicrometerGenerationTraceContextBridge(Tracer tracer,
                                                  Propagator propagator,
                                                  GenerationTraceParentRegistry parentRegistry) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.propagator = Objects.requireNonNull(propagator, "propagator");
        this.parentRegistry = Objects.requireNonNull(parentRegistry, "parentRegistry");
    }

    MicrometerGenerationTraceContextBridge(Tracer tracer, Propagator propagator) {
        this(tracer, propagator, new GenerationTraceParentRegistry());
    }

    @Override
    public GenerationTraceContext capture() {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan == null || currentSpan.context() == null) {
            return GenerationTraceContext.empty();
        }
        Map<String, String> carrier = new LinkedHashMap<>();
        propagator.inject(currentSpan.context(), carrier, Map::put);
        return GenerationTraceContext.fromCarrier(carrier);
    }

    @Override
    public Runnable wrap(GenerationTraceContext context,
                         String spanName,
                         Map<String, String> tags,
                         Runnable task) {
        Objects.requireNonNull(task, "task");
        GenerationTraceContext traceContext = context == null
                ? GenerationTraceContext.empty()
                : context;
        String normalizedSpanName = normalizeSpanName(spanName);
        Map<String, String> normalizedTags = normalizeTags(tags);
        return () -> continueTrace(traceContext, normalizedSpanName, normalizedTags, task);
    }

    private void continueTrace(GenerationTraceContext context,
                               String spanName,
                               Map<String, String> tags,
                               Runnable task) {
        Span.Builder builder = context.isEmpty()
                ? tracer.spanBuilder()
                : propagator.extract(context.carrier(), Map::get);
        Span span = builder
                .name(spanName)
                .kind(Span.Kind.CONSUMER)
                .start();
        tags.forEach(span::tag);
        try (GenerationTraceParentRegistry.Registration ignoredParent = parentRegistry.register(
                tags.get("generation.task.id"), span.context());
             Tracer.SpanInScope ignoredScope = tracer.withSpan(span)) {
            task.run();
        } catch (RuntimeException | Error failure) {
            span.error(failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    private String normalizeSpanName(String value) {
        if (value == null || value.isBlank()) {
            return "generation.task.execute";
        }
        String normalized = value.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }

    private Map<String, String> normalizeTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            if (normalized.size() >= MAX_TAGS || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().trim().replaceAll("[^A-Za-z0-9_.-]", "_");
            if (key.isBlank()) {
                continue;
            }
            String value = entry.getValue().trim();
            normalized.put(
                    key.length() <= 96 ? key : key.substring(0, 96),
                    value.length() <= MAX_TAG_VALUE_LENGTH
                            ? value
                            : value.substring(0, MAX_TAG_VALUE_LENGTH)
            );
        }
        return Map.copyOf(normalized);
    }
}
