package com.rush.rushaicodemother.orchestration.runtime.tracing;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Persistable W3C trace carrier for work that crosses durable queue and process boundaries.
 *
 * <p>Only trace identity is retained. Baggage is deliberately excluded because it may contain
 * user or tenant data and would otherwise become part of the durable task payload.</p>
 */
public record GenerationTraceContext(String traceparent, String tracestate) {

    private static final String TRACEPARENT = "traceparent";
    private static final String TRACESTATE = "tracestate";
    private static final int MAX_TRACESTATE_LENGTH = 512;
    private static final Pattern TRACEPARENT_PATTERN = Pattern.compile(
            "^00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$"
    );

    public GenerationTraceContext {
        traceparent = normalize(traceparent);
        tracestate = normalize(tracestate);
        if (traceparent == null) {
            tracestate = null;
        } else if (!TRACEPARENT_PATTERN.matcher(traceparent).matches()) {
            throw new IllegalArgumentException("traceparent is not a supported W3C trace context");
        }
        if (tracestate != null && (tracestate.length() > MAX_TRACESTATE_LENGTH
                || containsControlCharacter(tracestate))) {
            throw new IllegalArgumentException("tracestate is invalid");
        }
    }

    public static GenerationTraceContext empty() {
        return new GenerationTraceContext(null, null);
    }

    public static GenerationTraceContext fromCarrier(Map<String, String> carrier) {
        if (carrier == null || carrier.isEmpty()) {
            return empty();
        }
        String traceparent = null;
        String tracestate = null;
        for (Map.Entry<String, String> entry : carrier.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (TRACEPARENT.equals(key)) {
                traceparent = entry.getValue();
            } else if (TRACESTATE.equals(key)) {
                tracestate = entry.getValue();
            }
        }
        return new GenerationTraceContext(traceparent, tracestate);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return traceparent == null;
    }

    public Map<String, String> carrier() {
        if (traceparent == null) {
            return Map.of();
        }
        return tracestate == null
                ? Map.of(TRACEPARENT, traceparent)
                : Map.of(TRACEPARENT, traceparent, TRACESTATE, tracestate);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7F);
    }
}
