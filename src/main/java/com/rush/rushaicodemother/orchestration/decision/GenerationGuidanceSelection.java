package com.rush.rushaicodemother.orchestration.decision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 随场景决策冻结的 recipe 与 skill 快照。
 *
 * <p>选择结果保存完整载荷而不是只保存 ID，保证任务恢复时不会因技能文件或内置 recipe
 * 版本变化而获得另一套工程指引。构造时递归复制动态载荷，调用方无法在冻结后修改。</p>
 */
public record GenerationGuidanceSelection(
        List<Map<String, Object>> recipes,
        List<Map<String, Object>> skills
) {

    public GenerationGuidanceSelection {
        recipes = immutablePayloads(recipes, "recipes");
        skills = immutablePayloads(skills, "skills");
        requireDistinctIds(recipes, "recipes");
        requireDistinctIds(skills, "skills");
    }

    public static GenerationGuidanceSelection empty() {
        return new GenerationGuidanceSelection(List.of(), List.of());
    }

    public List<String> recipeIds() {
        return idsOf(recipes);
    }

    public List<String> skillIds() {
        return idsOf(skills);
    }

    /** 返回 recipe 与 skill 声明的去重模块顺序。 */
    public List<String> modules() {
        return combinedStringValues("modules");
    }

    /** 返回 recipe 与 skill 声明的去重上下文文件提示。 */
    public List<String> contextFileHints() {
        return combinedStringValues("contextFileHints");
    }

    private List<String> combinedStringValues(String fieldName) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        appendStringValues(recipes, fieldName, values);
        appendStringValues(skills, fieldName, values);
        return List.copyOf(values);
    }

    private static void appendStringValues(
            List<Map<String, Object>> payloads,
            String fieldName,
            Set<String> result
    ) {
        for (Map<String, Object> payload : payloads) {
            Object value = payload.get(fieldName);
            if (value == null) {
                continue;
            }
            if (!(value instanceof List<?> items)) {
                throw invalid(fieldName, "必须为字符串列表");
            }
            for (Object item : items) {
                if (!(item instanceof String text) || text.isBlank()) {
                    throw invalid(fieldName, "只能包含非空字符串");
                }
                result.add(text.trim());
            }
        }
    }

    private static List<Map<String, Object>> immutablePayloads(
            List<Map<String, Object>> payloads,
            String fieldName
    ) {
        if (payloads == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(payloads.size());
        for (Map<String, Object> payload : payloads) {
            if (payload == null) {
                throw invalid(fieldName, "不能包含空载荷");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> immutable = (Map<String, Object>) immutableValue(
                    payload, fieldName);
            result.add(immutable);
        }
        return List.copyOf(result);
    }

    private static Object immutableValue(Object value, String fieldName) {
        if (value == null) {
            throw invalid(fieldName, "载荷值不能为空");
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                    throw invalid(fieldName, "载荷键必须为非空字符串");
                }
                copied.put(key, immutableValue(entry.getValue(), fieldName + "." + key));
            }
            return Collections.unmodifiableMap(copied);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> immutableValue(item, fieldName))
                    .toList();
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw invalid(fieldName, "包含不支持的载荷类型: " + value.getClass().getSimpleName());
    }

    private static void requireDistinctIds(
            List<Map<String, Object>> payloads,
            String fieldName
    ) {
        List<String> ids = idsOf(payloads);
        Set<String> uniqueIds = new LinkedHashSet<>(ids);
        if (uniqueIds.size() != ids.size()) {
            throw invalid(fieldName, "ID 不能重复");
        }
    }

    private static List<String> idsOf(List<Map<String, Object>> payloads) {
        return payloads.stream()
                .map(payload -> payload.get("id"))
                .map(value -> {
                    if (!(value instanceof String id) || id.isBlank()) {
                        throw invalid("id", "必须为非空字符串");
                    }
                    return id.trim();
                })
                .toList();
    }

    private static IllegalArgumentException invalid(String fieldName, String reason) {
        return new IllegalArgumentException("生成工程指引字段 " + fieldName + ": " + reason);
    }
}
