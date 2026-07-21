package com.rush.rushaicodemother.ai.prompt.release;

import java.util.LinkedHashMap;
import java.util.Map;

/** Non-sensitive inventory of immutable versions that the running artifact can activate. */
public record PromptReleaseCapabilities(
        Map<String, Map<String, String>> contentHashesByPromptAndVersion
) {
    public PromptReleaseCapabilities {
        Map<String, Map<String, String>> copied = new LinkedHashMap<>();
        if (contentHashesByPromptAndVersion != null) {
            contentHashesByPromptAndVersion.forEach((key, versions) ->
                    copied.put(key, versions == null ? Map.of() : Map.copyOf(versions)));
        }
        contentHashesByPromptAndVersion = Map.copyOf(copied);
    }

    public static PromptReleaseCapabilities empty() {
        return new PromptReleaseCapabilities(Map.of());
    }

    public boolean supports(String promptKey, String version) {
        return contentHashesByPromptAndVersion
                .getOrDefault(promptKey, Map.of())
                .containsKey(version);
    }
}
