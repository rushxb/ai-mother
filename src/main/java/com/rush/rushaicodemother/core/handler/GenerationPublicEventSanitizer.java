package com.rush.rushaicodemother.core.handler;

import com.rush.rushaicodemother.infrastructure.diagnostic.PublicDiagnosticSanitizer;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将内部生成事件投射到向用户公开的有界、安全的合约上。
 *
 * <p>Tool 参数和原始执行结果属于模型/工具运行时，不得
 * 重用为 SSE、重放、跟踪或工作内存有效负载。保留文件操作预览
 * 仅作为明确选择、编辑和大小限制的字段。</p>
 */
public final class GenerationPublicEventSanitizer {

    private static final int MAX_EVENT_TYPE_LENGTH = 64;
    private static final int MAX_EVENT_TEXT_LENGTH = 8_000;
    private static final int MAX_TOOL_TEXT_LENGTH = 512;
    private static final int MAX_STRUCTURED_STRING_LENGTH = 2_000;
    private static final int MAX_FILE_PREVIEW_LENGTH = 8_000;
    private static final int MAX_MAP_ENTRIES = 48;
    private static final int MAX_COLLECTION_ENTRIES = 48;
    private static final int MAX_STRUCTURE_DEPTH = 5;

    private static final Set<String> INTERNAL_TOOL_FIELDS = Set.of(
            "arguments", "result", "rawarguments", "rawresult", "rawpayload", "payload"
    );
    private static final Set<String> FILE_PREVIEW_FIELDS = Set.of(
            "content", "oldcontent", "newcontent"
    );
    private static final List<String> SENSITIVE_KEY_FRAGMENTS = List.of(
            "password", "passwd", "secret", "authorization", "cookie", "apikey", "api_key",
            "access_token", "refreshtoken", "refresh_token", "privatekey", "private_key"
    );

    private GenerationPublicEventSanitizer() {
    }

    /** 当事件是私有的且不得跨越公共边界时，返回 {@code null}。 */
    public static GenerationStreamEvent sanitize(GenerationStreamEvent event) {
        if (event == null || GenerationStreamEvent.AI_THINKING_DELTA.equals(event.getType())) {
            return null;
        }
        String type = PublicDiagnosticSanitizer.sanitizeSingleLine(
                event.getType(), MAX_EVENT_TYPE_LENGTH);
        boolean toolEvent = GenerationStreamEvent.TOOL_CALL.equals(type)
                || GenerationStreamEvent.TOOL_RESULT.equals(type);
        Map<String, Object> data = toolEvent
                ? sanitizeToolData(event.getData())
                : sanitizeMap(event.getData(), 0, MAX_STRUCTURED_STRING_LENGTH);
        String text = toolEvent
                ? toolSummary(type, data)
                : PublicDiagnosticSanitizer.sanitizeForPublicOutput(
                        event.getText(), MAX_EVENT_TEXT_LENGTH);
        return GenerationStreamEvent.builder()
                .type(type)
                .text(text)
                .data(data == null || data.isEmpty() ? null : Map.copyOf(data))
                .build();
    }

    /** 清理工具{@code Data}中的敏感或不安全内容。 */
    private static Map<String, Object> sanitizeToolData(Map<String, Object> source) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        boolean previewTruncated = false;
        int copied = 0;
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (copied >= MAX_MAP_ENTRIES) {
                sanitized.put("metadataTruncated", true);
                break;
            }
            String key = safeKey(entry.getKey());
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (key.isBlank() || INTERNAL_TOOL_FIELDS.contains(normalizedKey)
                    || isSensitiveKey(normalizedKey)) {
                continue;
            }
            Object value;
            if (FILE_PREVIEW_FIELDS.contains(normalizedKey)) {
                String original = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                value = PublicDiagnosticSanitizer.sanitizeForPublicOutput(
                        original, MAX_FILE_PREVIEW_LENGTH);
                previewTruncated = previewTruncated
                        || original.length() > MAX_FILE_PREVIEW_LENGTH
                        || String.valueOf(value).contains("diagnostic output truncated");
            } else {
                value = sanitizeValue(entry.getValue(), 1, MAX_STRUCTURED_STRING_LENGTH);
            }
            if (value != null) {
                sanitized.put(key, value);
                copied++;
            }
        }
        if (previewTruncated) {
            sanitized.put("previewTruncated", true);
        }
        return sanitized;
    }

    /** 将当前对象转换为{@code ol}汇总。 */
    private static String toolSummary(String type, Map<String, Object> data) {
        String prefix = GenerationStreamEvent.TOOL_CALL.equals(type) ? "[调用工具]" : "[工具完成]";
        String toolName = value(data, "toolName");
        String filePath = value(data, "filePath");
        StringBuilder summary = new StringBuilder(prefix);
        if (!toolName.isBlank()) {
            summary.append(' ').append(toolName);
        }
        if (!filePath.isBlank()) {
            summary.append(' ').append(filePath);
        }
        return PublicDiagnosticSanitizer.sanitizeSingleLine(summary.toString(), MAX_TOOL_TEXT_LENGTH);
    }

    private static String value(Map<String, Object> data, String key) {
        if (data == null || data.get(key) == null) {
            return "";
        }
        return PublicDiagnosticSanitizer.sanitizeSingleLine(
                String.valueOf(data.get(key)), MAX_TOOL_TEXT_LENGTH);
    }

    /** 清理映射中的敏感或不安全内容。 */
    private static Map<String, Object> sanitizeMap(Map<?, ?> source,
                                                   int depth,
                                                   int maxStringLength) {
        if (source == null || source.isEmpty() || depth >= MAX_STRUCTURE_DEPTH) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        int copied = 0;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (copied >= MAX_MAP_ENTRIES) {
                sanitized.put("metadataTruncated", true);
                break;
            }
            String key = safeKey(entry.getKey());
            if (key.isBlank() || isSensitiveKey(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Object value = sanitizeValue(entry.getValue(), depth + 1, maxStringLength);
            if (value != null) {
                sanitized.put(key, value);
                copied++;
            }
        }
        return sanitized;
    }

    /** 清理值中的敏感或不安全内容。 */
    private static Object sanitizeValue(Object value, int depth, int maxStringLength) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence || value instanceof Character || value instanceof Enum<?>) {
            return PublicDiagnosticSanitizer.sanitizeForPublicOutput(
                    String.valueOf(value), maxStringLength);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (depth >= MAX_STRUCTURE_DEPTH) {
            return "[structured value omitted]";
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap(map, depth, maxStringLength);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> sanitized = new ArrayList<>();
            int copied = 0;
            for (Object item : collection) {
                if (copied >= MAX_COLLECTION_ENTRIES) {
                    sanitized.add("[collection truncated]");
                    break;
                }
                Object safeItem = sanitizeValue(item, depth + 1, maxStringLength);
                if (safeItem != null) {
                    sanitized.add(safeItem);
                    copied++;
                }
            }
            return List.copyOf(sanitized);
        }
        if (value.getClass().isArray()) {
            List<Object> sanitized = new ArrayList<>();
            int length = Math.min(Array.getLength(value), MAX_COLLECTION_ENTRIES);
            for (int index = 0; index < length; index++) {
                Object safeItem = sanitizeValue(Array.get(value, index), depth + 1, maxStringLength);
                if (safeItem != null) {
                    sanitized.add(safeItem);
                }
            }
            if (Array.getLength(value) > MAX_COLLECTION_ENTRIES) {
                sanitized.add("[array truncated]");
            }
            return List.copyOf(sanitized);
        }
        return PublicDiagnosticSanitizer.sanitizeForPublicOutput(
                safeString(value), maxStringLength);
    }

    private static String safeKey(Object key) {
        return PublicDiagnosticSanitizer.sanitizeSingleLine(
                key == null ? "" : String.valueOf(key), 96);
    }

    private static boolean isSensitiveKey(String normalizedKey) {
        String compactKey = normalizedKey.replaceAll("[^a-z0-9]", "");
        return SENSITIVE_KEY_FRAGMENTS.stream().anyMatch(normalizedKey::contains)
                || compactKey.endsWith("token")
                || compactKey.endsWith("credential")
                || compactKey.endsWith("credentials");
    }

    /** 返回安全{@code String}。 */
    private static String safeString(Object value) {
        try {
            return String.valueOf(value);
        } catch (RuntimeException exception) {
            return value.getClass().getSimpleName() + "[public value unavailable]";
        }
    }
}
