package com.rush.rushaicodemother.orchestration.context;

import java.util.Map;

/** 一个来源感知上下文块。 */
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
