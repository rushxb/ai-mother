package com.rush.rushaicodemother.orchestration.context;

import java.util.Map;

/** One provenance-aware context block. */
public record AiContextPackSection(
        AiContextPackSectionType type,
        String title,
        String content,
        int priority,
        Map<String, Object> metadata
) {
    public AiContextPackSection {
        if (type == null) {
            throw new IllegalArgumentException("context pack section type is required");
        }
        title = title == null ? "" : title;
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean blank() {
        return content.isBlank();
    }
}
