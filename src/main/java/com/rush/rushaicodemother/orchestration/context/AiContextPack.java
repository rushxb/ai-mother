package com.rush.rushaicodemother.orchestration.context;

import cn.hutool.crypto.digest.DigestUtil;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Structured, provenance-aware context package for AI generation.
 *
 * <p>The pack keeps long-term memory, short-term traces and usage rules as typed sections before
 * rendering. That gives future route/model policies a stable object to inspect instead of parsing
 * an opaque prompt string.</p>
 */
public record AiContextPack(
        Long appId,
        String appName,
        String targetType,
        List<AiContextPackSection> sections
) {
    private static final int SCHEMA_VERSION = 1;
    private static final Pattern PACK_CONTROL_MARKER = Pattern.compile(
            "(?is)\\[\\s*/?\\s*(?:AI_CONTEXT_PACK|SECTION)\\b[^\\]]{0,512}\\]"
    );

    public AiContextPack {
        appName = appName == null ? "" : appName;
        targetType = targetType == null ? "unknown" : targetType;
        sections = sections == null
                ? List.of()
                : sections.stream().filter(section -> section != null && !section.blank()).toList();
    }

    public boolean empty() {
        return sections.isEmpty();
    }

    public String digest() {
        return DigestUtil.sha256Hex(render());
    }

    public String render() {
        if (sections.isEmpty()) {
            return "";
        }
        String body = sections.stream()
                .sorted(Comparator.comparingInt(AiContextPackSection::priority))
                .map(this::renderSection)
                .collect(Collectors.joining("\n"));
        return """
                [AI_CONTEXT_PACK schema=%d appId=%s targetType=%s digest=%s]
                %s
                [/AI_CONTEXT_PACK]
                """.formatted(
                SCHEMA_VERSION,
                appId,
                attribute(targetType, "unknown"),
                DigestUtil.sha256Hex(body),
                body
        ).trim();
    }

    private String renderSection(AiContextPackSection section) {
        String content = neutralizePackControlMarkers(section.content());
        String trust = metadata(section, "trust", defaultTrust(section.type()));
        String source = metadata(
                section,
                "source",
                section.type().name().toLowerCase(Locale.ROOT)
        );
        return """
                [SECTION type=%s priority=%d trust=%s source=%s digest=%s title="%s"]
                %s
                [/SECTION]
                """.formatted(
                section.type().name().toLowerCase(Locale.ROOT),
                section.priority(),
                attribute(trust, "unknown"),
                attribute(source, "unknown"),
                DigestUtil.sha256Hex(content),
                escape(section.title()),
                content
        ).trim();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", " ")
                .replace("\n", " ")
                .replace("[", "(")
                .replace("]", ")")
                .replace("\"", "'");
    }

    private String neutralizePackControlMarkers(String value) {
        return PACK_CONTROL_MARKER.matcher(value == null ? "" : value)
                .replaceAll("[context-pack-control-marker-neutralized]");
    }

    private String metadata(AiContextPackSection section, String key, String fallback) {
        Object value = section.metadata().get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private String defaultTrust(AiContextPackSectionType type) {
        return switch (type) {
            case USAGE_RULE -> "system_context_rule";
            case APP_SCOPE -> "trusted_application_scope";
            default -> "untrusted_history";
        };
    }

    private String attribute(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._:-]", "_");
        if (normalized.isBlank()) {
            return fallback;
        }
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }
}
