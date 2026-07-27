package com.rush.rushaicodemother.memory;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.infrastructure.diagnostic.PublicDiagnosticSanitizer;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 在语义内存到达任何存储后端之前强制执行共享数据契约。 */
public final class SemanticMemoryGovernancePolicy {

    public static final int MAX_CONTENT_UTF8_BYTES = 30_000;
    public static final int MAX_METADATA_UTF8_BYTES = 4_096;
    public static final int MAX_TASK_ID_LENGTH = 128;
    public static final int MAX_TOP_K = 50;
    private static final int MAX_CONTENT_CHARS = 8_000;
    private static final int MAX_METADATA_STRING_CHARS = 512;
    private static final int MAX_METADATA_KEYS = 24;
    private static final Pattern MEMORY_ID = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern TASK_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern SOURCE = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final List<String> OPTIONAL_METADATA_KEYS = List.of(
            "status",
            "taskStatus",
            "orchestrationMode",
            "targetType",
            "signalSource",
            "rating",
            "ratingBucket",
            "outcome",
            "improvementCandidate",
            "qualityGate",
            "route",
            "errorCategory"
    );
    private static final Set<String> PERSISTED_METADATA_KEYS = Set.of(
            "schemaVersion",
            "source",
            "trust",
            "contentDigest",
            "status",
            "taskStatus",
            "orchestrationMode",
            "targetType",
            "signalSource",
            "rating",
            "ratingBucket",
            "outcome",
            "improvementCandidate",
            "qualityGate",
            "route",
            "errorCategory"
    );

    private SemanticMemoryGovernancePolicy() {
    }

    public static String sanitizeContent(String content) {
        String sanitized = PublicDiagnosticSanitizer.sanitizeForPublicOutput(content, MAX_CONTENT_CHARS);
        return truncateUtf8(sanitized, MAX_CONTENT_UTF8_BYTES);
    }

    public static Map<String, Object> governMetadata(Map<String, Object> metadata, String content) {
        Map<String, Object> governed = new LinkedHashMap<>();
        governed.put("schemaVersion", "v2");
        governed.put("source", source(metadata));
        governed.put("trust", "untrusted_history");
        governed.put("contentDigest", DigestUtil.sha256Hex(content));
        if (metadata != null) {
            for (String key : OPTIONAL_METADATA_KEYS) {
                Object value = scalar(metadata.get(key));
                if (value == null) {
                    continue;
                }
                governed.put(key, value);
                if (metadataBytes(governed) > MAX_METADATA_UTF8_BYTES) {
                    governed.remove(key);
                }
            }
        }
        validateMetadata(governed);
        return Map.copyOf(governed);
    }

    public static void validateMemory(SemanticMemory memory, int expectedDimension) {
        validateStoredMemory(memory);
        validateVector(memory.embedding(), expectedDimension);
    }

    public static void validateMemory(SemanticMemory memory) {
        validateStoredMemory(memory);
        float[] embedding = memory.embedding();
        validateVector(embedding, embedding.length);
    }

    public static void validateStoredMemory(SemanticMemory memory) {
        if (memory == null
                || memory.id() == null || !MEMORY_ID.matcher(memory.id()).matches()
                || !positive(memory.tenantId())
                || !positive(memory.appId())
                || !positive(memory.userId())
                || memory.taskId() == null || !TASK_ID.matcher(memory.taskId()).matches()
                || memory.type() == null
                || memory.content() == null || memory.content().isBlank()
                || utf8Length(memory.content()) > MAX_CONTENT_UTF8_BYTES
                || memory.createdAt() == null) {
            throw new IllegalArgumentException("invalid semantic memory");
        }
        validateMetadata(memory.metadata());
        if (!"v2".equals(memory.metadata().get("schemaVersion"))
                || !"untrusted_history".equals(memory.metadata().get("trust"))
                || !DigestUtil.sha256Hex(memory.content()).equals(memory.metadata().get("contentDigest"))
                || !(memory.metadata().get("source") instanceof String source)
                || !SOURCE.matcher(source).matches()) {
            throw new IllegalArgumentException("semantic memory reserved metadata is invalid");
        }
    }

    public static void validateQuery(SemanticMemoryQuery query, int expectedDimension) {
        if (query == null
                || !positive(query.tenantId())
                || !positive(query.appId())
                || query.topK() <= 0 || query.topK() > MAX_TOP_K
                || !Double.isFinite(query.minimumScore())
                || query.minimumScore() < -1.0 || query.minimumScore() > 1.0) {
            throw new IllegalArgumentException("invalid semantic memory query");
        }
        validateVector(query.embedding(), expectedDimension);
    }

    public static void validateQuery(SemanticMemoryQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("invalid semantic memory query");
        }
        float[] embedding = query.embedding();
        validateQuery(query, embedding.length);
    }

    public static void validateMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.size() > MAX_METADATA_KEYS
                || !PERSISTED_METADATA_KEYS.containsAll(metadata.keySet())
                || metadataBytes(metadata) > MAX_METADATA_UTF8_BYTES) {
            throw new IllegalArgumentException("semantic memory metadata violates the storage policy");
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (entry.getKey() == null || !persistedScalar(entry.getValue())) {
                throw new IllegalArgumentException("semantic memory metadata must contain scalar values only");
            }
        }
    }

    public static String truncateUtf8(String value, int maxBytes) {
        if (value == null || value.isEmpty() || maxBytes <= 0) {
            return "";
        }
        if (utf8Length(value) <= maxBytes) {
            return value;
        }
        byte[] suffix = "...".getBytes(StandardCharsets.UTF_8);
        int available = Math.max(0, maxBytes - suffix.length);
        StringBuilder truncated = new StringBuilder(value.length());
        int used = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            String token = new String(Character.toChars(codePoint));
            int tokenBytes = utf8Length(token);
            if (used + tokenBytes > available) {
                break;
            }
            truncated.append(token);
            used += tokenBytes;
            offset += Character.charCount(codePoint);
        }
        return truncated.append("...").toString();
    }

    public static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void validateVector(float[] vector, int expectedDimension) {
        if (expectedDimension <= 0 || vector == null || vector.length != expectedDimension) {
            throw new IllegalArgumentException("semantic memory embedding dimension mismatch");
        }
        double norm = 0.0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("semantic memory embedding contains a non-finite value");
            }
            norm += (double) value * value;
        }
        if (!Double.isFinite(norm) || norm <= 0.0) {
            throw new IllegalArgumentException("semantic memory embedding must have a finite non-zero norm");
        }
    }

    private static String source(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("source");
        String candidate = value == null ? "generation_task" : String.valueOf(value).trim().toLowerCase();
        return SOURCE.matcher(candidate).matches() ? candidate : "generation_task";
    }

    private static Object scalar(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Float number) {
            return Float.isFinite(number) ? number : null;
        }
        if (value instanceof Double number) {
            return Double.isFinite(number) ? number : null;
        }
        if (value instanceof Number number) {
            double converted = number.doubleValue();
            return Double.isFinite(converted) ? converted : null;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        String normalized = String.valueOf(value)
                .replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", " ")
                .trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.codePointCount(0, normalized.length()) <= MAX_METADATA_STRING_CHARS
                ? normalized
                : truncateCodePoints(normalized, MAX_METADATA_STRING_CHARS);
    }

    private static boolean persistedScalar(Object value) {
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return true;
        }
        if (value instanceof Float number) {
            return Float.isFinite(number);
        }
        if (value instanceof Double number) {
            return Double.isFinite(number);
        }
        if (value instanceof Number number) {
            return Double.isFinite(number.doubleValue());
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return false;
        }
        return text.codePointCount(0, text.length()) <= MAX_METADATA_STRING_CHARS;
    }

    private static String truncateCodePoints(String value, int maxCodePoints) {
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end);
    }

    private static int metadataBytes(Map<String, Object> metadata) {
        return utf8Length(JSONUtil.toJsonStr(metadata));
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }
}
