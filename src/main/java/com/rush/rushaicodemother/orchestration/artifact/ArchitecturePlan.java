package com.rush.rushaicodemother.orchestration.artifact;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Architect 与 Code 之间的强类型架构计划制品。
 *
 * <p>该模块集中拥有持久载荷的 schema、工程类型绑定和字段类型校验。
 * 恢复检查点时必须先通过本类还原为领域对象，禁止 Code 节点
 * 直接解释动态 Map，避免其他任务或其他工程类型的计划被误用。</p>
 */
public record ArchitecturePlan(
        List<String> modules,
        List<String> constraints,
        CodeGenTypeEnum targetType,
        boolean parallelizable
) {

    public static final String KEY = "architecture_plan";

    private static final String SCHEMA_VERSION = "v1";
    private static final String ROLE = "Architect";
    private static final String TITLE = "架构规划";

    public ArchitecturePlan {
        modules = normalizeTextList(modules);
        constraints = normalizeTextList(constraints);
        if (modules.isEmpty()) {
            throw invalidField("modules", "至少需要一个模块");
        }
        if (targetType == null) {
            throw invalidField("targetType", "不能为空");
        }
    }

    /** 从检查点恢复并校验计划必须属于当前工程类型。 */
    public static ArchitecturePlan fromArtifact(
            GenerationArtifact artifact,
            CodeGenTypeEnum expectedTargetType
    ) {
        Objects.requireNonNull(artifact, "架构计划制品不能为空");
        Objects.requireNonNull(expectedTargetType, "架构计划期望目标类型不能为空");
        if (!KEY.equals(artifact.key())) {
            throw invalidField("key", "制品类型不匹配: " + artifact.key());
        }

        Map<String, Object> payload = Objects.requireNonNull(
                artifact.payload(), "架构计划制品载荷不能为空");
        if (payload.containsKey("schemaVersion")) {
            requireExactText(payload.get("schemaVersion"), "schemaVersion", SCHEMA_VERSION);
        }

        List<String> modules = requireTextList(payload.get("modules"), "modules", false);
        List<String> constraints = requireTextList(payload.get("constraints"), "constraints", true);
        CodeGenTypeEnum targetType = readTargetType(payload, expectedTargetType);
        if (targetType != expectedTargetType) {
            throw invalidField(
                    "targetType",
                    "架构计划目标类型与当前工程不一致: "
                            + targetType.getValue() + " != " + expectedTargetType.getValue()
            );
        }

        boolean parallelizable = payload.containsKey("parallelizable")
                ? requireBoolean(payload.get("parallelizable"), "parallelizable")
                : modules.size() > 1;
        return new ArchitecturePlan(modules, constraints, targetType, parallelizable);
    }

    /** 转换为 DAG 可持久制品。 */
    public GenerationArtifact toArtifact() {
        return GenerationArtifact.of(KEY, ROLE, TITLE, toPayload());
    }

    /** 转换为稳定的版本化载荷。 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("modules", modules);
        payload.put("constraints", constraints);
        payload.put("targetType", targetType.getValue());
        payload.put("parallelizable", parallelizable);
        return Map.copyOf(payload);
    }

    private static CodeGenTypeEnum readTargetType(
            Map<String, Object> payload,
            CodeGenTypeEnum legacyFallback
    ) {
        if (!payload.containsKey("targetType")) {
            // 旧检查点尚未持久该字段；只有“缺失”可使用当前任务类型迁移。
            return legacyFallback;
        }
        String value = requireText(payload.get("targetType"), "targetType");
        CodeGenTypeEnum targetType = CodeGenTypeEnum.getEnumByValue(value);
        if (targetType == null) {
            throw invalidField("targetType", "未知工程类型: " + value);
        }
        return targetType;
    }

    private static List<String> requireTextList(
            Object value,
            String fieldName,
            boolean optional
    ) {
        if (value == null && optional) {
            return List.of();
        }
        if (!(value instanceof List<?> source)) {
            throw invalidField(fieldName, "必须是字符串数组");
        }
        List<String> result = new ArrayList<>(source.size());
        for (Object item : source) {
            result.add(requireText(item, fieldName));
        }
        return List.copyOf(result);
    }

    private static List<String> normalizeTextList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private static boolean requireBoolean(Object value, String fieldName) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw invalidField(fieldName, "必须是布尔值");
    }

    private static String requireText(Object value, String fieldName) {
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        throw invalidField(fieldName, "必须是非空字符串");
    }

    private static void requireExactText(Object value, String fieldName, String expected) {
        String actual = requireText(value, fieldName);
        if (!expected.equals(actual)) {
            throw invalidField(fieldName, "不支持的值: " + actual);
        }
    }

    private static IllegalArgumentException invalidField(String fieldName, String reason) {
        return new IllegalArgumentException("架构计划字段 " + fieldName + " 无效: " + reason);
    }
}
