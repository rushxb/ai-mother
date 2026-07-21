package com.rush.rushaicodemother.ai.prompt.release;

import java.util.LinkedHashMap;
import java.util.Map;

/** Atomic database release bundle loaded by every application node. */
public record PromptReleaseState(
        long revision,
        Map<String, PromptReleaseRecord> releases
) {
    public PromptReleaseState {
        if (revision < 0) {
            throw new IllegalArgumentException("prompt release revision cannot be negative");
        }
        releases = releases == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(releases));
    }

    public static PromptReleaseState empty() {
        return new PromptReleaseState(0L, Map.of());
    }
}
