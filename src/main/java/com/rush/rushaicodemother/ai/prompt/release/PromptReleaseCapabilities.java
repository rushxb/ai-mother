package com.rush.rushaicodemother.ai.prompt.release;

import java.util.LinkedHashMap;
import java.util.Map;

/** 运行工件可以激活的不可变版本的非敏感清单。 */
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
