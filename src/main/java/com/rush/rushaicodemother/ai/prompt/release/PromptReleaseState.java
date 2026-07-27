package com.rush.rushaicodemother.ai.prompt.release;

import java.util.LinkedHashMap;
import java.util.Map;

/** 每个应用程序节点加载的原子数据库发布包。 */
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
