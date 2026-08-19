package com.rush.rushaicodemother.orchestration.artifact;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Planner 需求制品的强类型事实模型。
 *
 * <p>本模块集中拥有执行模式派生、持久载荷结构和动态扩展列表的外层类型校验，
 * 调用方不再分别解释布尔值、模式字符串与 recipe/skill 列表。</p>
 */
public final class GenerationRequirementsArtifact {

    public static final String KEY = "requirements";

    private static final String ROLE = "Planner";
    private static final String TITLE = "需求与目标";
    private static final Set<String> INTENT_COVERAGE_FIELDS = Set.of(
            "complex",
            "targetType",
            "upgradeRequired",
            "patchFirst",
            "requiresBuild",
            "contextRecallSource",
            "contextRecallQuery",
            "goals"
    );

    private final boolean complex;
    private final boolean complexityDeclared;
    private final boolean intentCoverageFieldsDeclared;
    private final CodeGenTypeEnum targetType;
    private final boolean upgradeRequired;
    private final boolean patchFirst;
    private final boolean requiresBuild;
    private final String contextRecallSource;
    private final String contextRecallQuery;
    private final List<Map<String, Object>> indexHits;
    private final List<String> goals;
    private final List<Map<String, Object>> recipes;
    private final List<Map<String, Object>> skills;

    private GenerationRequirementsArtifact(boolean complex,
                                           boolean complexityDeclared,
                                           boolean intentCoverageFieldsDeclared,
                                           CodeGenTypeEnum targetType,
                                           boolean upgradeRequired,
                                           boolean patchFirst,
                                           boolean requiresBuild,
                                           String contextRecallSource,
                                           String contextRecallQuery,
                                           List<Map<String, Object>> indexHits,
                                           List<String> goals,
                                           List<Map<String, Object>> recipes,
                                           List<Map<String, Object>> skills) {
        this.complex = complex;
        this.complexityDeclared = complexityDeclared;
        this.intentCoverageFieldsDeclared = intentCoverageFieldsDeclared;
        this.targetType = Objects.requireNonNull(targetType, "需求制品目标类型不能为空");
        this.upgradeRequired = upgradeRequired;
        this.patchFirst = patchFirst;
        this.requiresBuild = requiresBuild;
        this.contextRecallSource = requireText(contextRecallSource, "上下文召回来源不能为空");
        this.contextRecallQuery = contextRecallQuery == null ? "" : contextRecallQuery.trim();
        this.indexHits = immutableMapList(indexHits, "indexHits");
        this.goals = immutableStringList(goals, "goals");
        this.recipes = immutableMapList(recipes, "recipes");
        this.skills = immutableMapList(skills, "skills");
    }

    /** 创建 Planner 本轮输出的规范需求事实。 */
    public static GenerationRequirementsArtifact create(
            boolean complex,
            CodeGenTypeEnum targetType,
            boolean upgradeRequired,
            boolean patchFirst,
            boolean requiresBuild,
            String contextRecallSource,
            String contextRecallQuery,
            List<Map<String, Object>> indexHits,
            List<String> goals,
            List<Map<String, Object>> recipes,
            List<Map<String, Object>> skills) {
        return new GenerationRequirementsArtifact(
                complex,
                true,
                true,
                targetType,
                upgradeRequired,
                patchFirst,
                requiresBuild,
                contextRecallSource,
                contextRecallQuery,
                indexHits,
                goals,
                recipes,
                skills
        );
    }

    /** 从通用持久制品恢复强类型需求事实。 */
    public static GenerationRequirementsArtifact fromArtifact(
            GenerationArtifact artifact,
            CodeGenTypeEnum fallbackTargetType) {
        Objects.requireNonNull(artifact, "需求制品不能为空");
        if (!KEY.equals(artifact.key())) {
            throw new IllegalArgumentException(
                    "制品类型不匹配，期望: " + KEY + "，实际: " + artifact.key());
        }
        Map<String, Object> payload = Objects.requireNonNull(
                artifact.payload(), "需求制品载荷不能为空");
        String targetTypeValue = optionalTextValue(payload, "targetType", "");
        CodeGenTypeEnum targetType = targetTypeValue.isBlank()
                ? fallbackTargetType
                : CodeGenTypeEnum.getEnumByValue(targetTypeValue);
        if (targetType == null) {
            throw invalidField("targetType", "不是有效的工程类型");
        }
        GenerationRequirementsArtifact restored = new GenerationRequirementsArtifact(
                optionalBoolean(payload, "complex", false),
                payload.containsKey("complex"),
                payload.keySet().containsAll(INTENT_COVERAGE_FIELDS),
                targetType,
                optionalBoolean(payload, "upgradeRequired", false),
                optionalBoolean(payload, "patchFirst", false),
                optionalBoolean(payload, "requiresBuild", false),
                optionalTextValue(payload, "contextRecallSource", "legacy_checkpoint"),
                optionalTextValue(payload, "contextRecallQuery", ""),
                optionalMapList(payload, "indexHits"),
                optionalStringList(payload, "goals"),
                optionalMapList(payload, "recipes"),
                optionalMapList(payload, "skills")
        );
        validateDerivedField(payload, "validationMode", restored.validationMode());
        validateDerivedField(payload, "generationMode", restored.generationMode());
        validateDerivedField(payload, "orchestrationMode", restored.orchestrationMode());
        return restored;
    }

    /** 转换为可写入 DAG 检查点的通用制品。 */
    public GenerationArtifact toArtifact() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("complex", complex);
        payload.put("targetType", targetType.getValue());
        payload.put("upgradeRequired", upgradeRequired);
        payload.put("patchFirst", patchFirst);
        payload.put("requiresBuild", requiresBuild);
        payload.put("validationMode", validationMode());
        payload.put("generationMode", generationMode());
        payload.put("orchestrationMode", orchestrationMode());
        payload.put("contextRecallSource", contextRecallSource);
        payload.put("contextRecallQuery", contextRecallQuery);
        payload.put("indexHits", indexHits);
        payload.put("goals", goals);
        payload.put("recipeIds", idsOf(recipes));
        payload.put("recipes", recipes);
        payload.put("skillIds", idsOf(skills));
        payload.put("skills", skills);
        return GenerationArtifact.of(KEY, ROLE, TITLE, payload);
    }

    public boolean complex() {
        return complex;
    }

    /**
     * 返回 Planner 是否显式给出复杂度结论。
     * 旧检查点缺失该事实时返回空值，避免把“未知”误判为“简单”。
     */
    public Optional<Boolean> plannedComplexity() {
        return complexityDeclared ? Optional.of(complex) : Optional.empty();
    }

    public CodeGenTypeEnum targetType() {
        return targetType;
    }

    public boolean upgradeRequired() {
        return upgradeRequired;
    }

    public boolean patchFirst() {
        return patchFirst;
    }

    public boolean requiresBuild() {
        return requiresBuild;
    }

    public String validationMode() {
        return requiresBuild ? "build_validation" : "review_only";
    }

    public String generationMode() {
        return patchFirst ? "patch_first_update" : "full_generation";
    }

    public String orchestrationMode() {
        return requiresBuild ? "heavy" : "light";
    }

    public String contextRecallSource() {
        return contextRecallSource;
    }

    public String contextRecallQuery() {
        return contextRecallQuery;
    }

    public List<Map<String, Object>> indexHits() {
        return indexHits;
    }

    public List<String> goals() {
        return goals;
    }

    public List<Map<String, Object>> recipes() {
        return recipes;
    }

    public List<Map<String, Object>> skills() {
        return skills;
    }

    /**
     * 判断当前需求事实能否作为任务完成时的意图覆盖证据。
     *
     * <p>兼容恢复允许旧检查点缺少部分字段，但完成证据必须更严格：关键规划事实需显式存在，
     * 原始诉求与目标列表不能为空，并且制品目标类型必须与本次执行一致。</p>
     */
    public boolean provesIntentCoverage(CodeGenTypeEnum expectedTargetType) {
        return intentCoverageFieldsDeclared
                && complexityDeclared
                && expectedTargetType != null
                && targetType == expectedTargetType
                && !"legacy_checkpoint".equals(contextRecallSource)
                && !contextRecallQuery.isBlank()
                && goals.stream().anyMatch(goal -> goal != null && !goal.isBlank());
    }

    private static List<String> idsOf(List<Map<String, Object>> payloads) {
        return payloads.stream()
                .map(payload -> payload.get("id"))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static void validateDerivedField(Map<String, Object> payload,
                                             String fieldName,
                                             String expectedValue) {
        if (!payload.containsKey(fieldName)) {
            return;
        }
        String persisted = requireTextValue(payload.get(fieldName), fieldName);
        if (!expectedValue.equals(persisted)) {
            throw invalidField(
                    fieldName,
                    "与源事实不一致: " + persisted + " != " + expectedValue);
        }
    }

    private static boolean requireBoolean(Object value, String fieldName) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw invalidField(fieldName, "必须为布尔值");
    }

    private static boolean optionalBoolean(Map<String, Object> payload,
                                           String fieldName,
                                           boolean defaultValue) {
        return payload.containsKey(fieldName)
                ? requireBoolean(payload.get(fieldName), fieldName)
                : defaultValue;
    }

    private static String requireTextValue(Object value, String fieldName) {
        if (value instanceof String text) {
            return text.trim();
        }
        throw invalidField(fieldName, "必须为字符串");
    }

    private static String optionalTextValue(Map<String, Object> payload,
                                            String fieldName,
                                            String defaultValue) {
        return payload.containsKey(fieldName)
                ? requireTextValue(payload.get(fieldName), fieldName)
                : defaultValue;
    }

    private static List<String> requireStringList(Object value, String fieldName) {
        if (!(value instanceof List<?> values)
                || values.stream().anyMatch(item -> !(item instanceof String))) {
            throw invalidField(fieldName, "必须为字符串列表");
        }
        return values.stream().map(String.class::cast).toList();
    }

    private static List<String> optionalStringList(Map<String, Object> payload,
                                                   String fieldName) {
        return payload.containsKey(fieldName)
                ? requireStringList(payload.get(fieldName), fieldName)
                : List.of();
    }

    private static List<Map<String, Object>> requireMapList(Object value, String fieldName) {
        if (!(value instanceof List<?> values)) {
            throw invalidField(fieldName, "必须为对象列表");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) {
                throw invalidField(fieldName, "必须为对象列表");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw invalidField(fieldName, "只能包含字符串键");
                }
                normalized.put(key, entry.getValue());
            }
            result.add(Map.copyOf(normalized));
        }
        return List.copyOf(result);
    }

    private static List<Map<String, Object>> optionalMapList(Map<String, Object> payload,
                                                             String fieldName) {
        return payload.containsKey(fieldName)
                ? requireMapList(payload.get(fieldName), fieldName)
                : List.of();
    }

    private static List<String> immutableStringList(List<String> values, String fieldName) {
        if (values == null || values.stream().anyMatch(Objects::isNull)) {
            throw invalidField(fieldName, "不能为空且不能包含空值");
        }
        return values.stream().map(String::trim).toList();
    }

    private static List<Map<String, Object>> immutableMapList(
            List<Map<String, Object>> values,
            String fieldName) {
        return requireMapList(values, fieldName);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static IllegalArgumentException invalidField(String fieldName, String reason) {
        return new IllegalArgumentException("需求制品字段 " + fieldName + reason);
    }
}
