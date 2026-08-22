package com.rush.rushaicodemother.orchestration.artifact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Context 节点输出的强类型项目上下文制品。
 *
 * <p>本模块集中拥有持久载荷的字段类型、规范路径、计数下界、
 * 仓库数据信任标记以及 recipe/skill 身份一致性，避免恢复后的动态 Map
 * 在 Code、Architect、NoPlan 和遥测链中被分别强转或静默降级。</p>
 */
public final class ContextSummaryArtifact {

    public static final String KEY = "context_summary";

    private static final String SCHEMA_VERSION = "v1";
    private static final String ROLE = "Context";
    private static final String TITLE = "项目上下文";
    private static final String CONTEXT_TRUST = "untrusted_repository_data";
    private static final Set<String> CORE_FIELDS = Set.of(
            "schemaVersion",
            "intent",
            "selectedFiles",
            "indexedFileCount",
            "indexedSymbolCount",
            "indexHits",
            "contextMode",
            "projectContext",
            "contextDigest",
            "contextTrust",
            "contextSecretsRedacted",
            "contextTruncated",
            "contextSourceChars",
            "memoryContext",
            "hasGeneratedCode",
            "recipeIds",
            "recipes",
            "skillIds",
            "skills"
    );

    private final RepositoryContext repositoryContext;
    private final ContextProtection protection;
    private final AgentGuidance guidance;
    private final Map<String, Object> payload;

    private ContextSummaryArtifact(
            RepositoryContext repositoryContext,
            ContextProtection protection,
            AgentGuidance guidance,
            Map<String, Object> sourcePayload
    ) {
        this.repositoryContext = Objects.requireNonNull(
                repositoryContext, "仓库上下文不能为空");
        this.protection = Objects.requireNonNull(
                protection, "上下文保护事实不能为空");
        this.guidance = Objects.requireNonNull(
                guidance, "Agent 指引不能为空");
        this.payload = buildPayload(sourcePayload);
    }

    /** 从 Context 节点的强类型事实创建可持久制品。 */
    public static ContextSummaryArtifact create(
            RepositoryContext repositoryContext,
            ContextProtection protection,
            AgentGuidance guidance
    ) {
        return new ContextSummaryArtifact(repositoryContext, protection, guidance, Map.of());
    }

    /** 从检查点恢复并严格验证上下文事实。 */
    public static ContextSummaryArtifact fromArtifact(GenerationArtifact artifact) {
        Objects.requireNonNull(artifact, "项目上下文制品不能为空");
        if (!KEY.equals(artifact.key())) {
            throw invalidField("key", "制品类型不匹配: " + artifact.key());
        }
        Map<String, Object> source = Objects.requireNonNull(
                artifact.payload(), "项目上下文制品载荷不能为空");
        if (source.containsKey("schemaVersion")) {
            requireExactText(source.get("schemaVersion"), "schemaVersion", SCHEMA_VERSION);
        }
        RepositoryContext repositoryContext = new RepositoryContext(
                requireText(source.get("intent"), "intent"),
                requireCanonicalFileList(source.get("selectedFiles"), "selectedFiles"),
                requireNonNegativeInt(source.get("indexedFileCount"), "indexedFileCount"),
                requireNonNegativeInt(source.get("indexedSymbolCount"), "indexedSymbolCount"),
                requireMapList(source.get("indexHits"), "indexHits", false),
                requireText(source.get("contextMode"), "contextMode"),
                requireString(source.get("projectContext"), "projectContext")
        );
        requireExactText(source.get("contextTrust"), "contextTrust", CONTEXT_TRUST);
        ContextProtection protection = new ContextProtection(
                requireText(source.get("contextDigest"), "contextDigest"),
                requireBoolean(source.get("contextSecretsRedacted"), "contextSecretsRedacted"),
                requireBoolean(source.get("contextTruncated"), "contextTruncated"),
                requireNonNegativeInt(source.get("contextSourceChars"), "contextSourceChars")
        );
        AgentGuidance guidance = new AgentGuidance(
                requireString(source.get("memoryContext"), "memoryContext"),
                requireBoolean(source.get("hasGeneratedCode"), "hasGeneratedCode"),
                requireMapList(source.get("recipes"), "recipes", true),
                requireMapList(source.get("skills"), "skills", true)
        );
        validateIds(source.get("recipeIds"), "recipeIds", guidance.recipes());
        validateIds(source.get("skillIds"), "skillIds", guidance.skills());
        return new ContextSummaryArtifact(repositoryContext, protection, guidance, source);
    }

    /** 转换为 DAG 可持久制品。 */
    public GenerationArtifact toArtifact() {
        return GenerationArtifact.of(KEY, ROLE, TITLE, payload);
    }

    public String projectContext() {
        return repositoryContext.projectContext();
    }

    public String memoryContext() {
        return guidance.memoryContext();
    }

    public List<String> selectedFiles() {
        return repositoryContext.selectedFiles();
    }

    public List<Map<String, Object>> recipes() {
        return guidance.recipes();
    }

    public List<Map<String, Object>> skills() {
        return guidance.skills();
    }

    public String contextMode() {
        return repositoryContext.contextMode();
    }

    public int indexedFileCount() {
        return repositoryContext.indexedFileCount();
    }

    public int indexedSymbolCount() {
        return repositoryContext.indexedSymbolCount();
    }

    public int indexHitCount() {
        return repositoryContext.indexHits().size();
    }

    public int projectContextChars() {
        return repositoryContext.projectContext().length();
    }

    private Map<String, Object> buildPayload(Map<String, Object> sourcePayload) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (sourcePayload != null) {
            sourcePayload.forEach((key, value) -> {
                if (!CORE_FIELDS.contains(key)) {
                    if (value == null) {
                        throw invalidField(key, "扩展字段不能为空");
                    }
                    normalized.put(key, value);
                }
            });
        }
        normalized.put("schemaVersion", SCHEMA_VERSION);
        normalized.put("intent", repositoryContext.intent());
        normalized.put("selectedFiles", repositoryContext.selectedFiles());
        normalized.put("indexedFileCount", repositoryContext.indexedFileCount());
        normalized.put("indexedSymbolCount", repositoryContext.indexedSymbolCount());
        normalized.put("indexHits", repositoryContext.indexHits());
        normalized.put("contextMode", repositoryContext.contextMode());
        normalized.put("projectContext", repositoryContext.projectContext());
        normalized.put("contextDigest", protection.digest());
        normalized.put("contextTrust", CONTEXT_TRUST);
        normalized.put("contextSecretsRedacted", protection.secretsRedacted());
        normalized.put("contextTruncated", protection.truncated());
        normalized.put("contextSourceChars", protection.sourceChars());
        normalized.put("memoryContext", guidance.memoryContext());
        normalized.put("hasGeneratedCode", guidance.hasGeneratedCode());
        normalized.put("recipeIds", idsOf(guidance.recipes(), "recipes"));
        normalized.put("recipes", guidance.recipes());
        normalized.put("skillIds", idsOf(guidance.skills(), "skills"));
        normalized.put("skills", guidance.skills());
        return Map.copyOf(normalized);
    }

    private static void validateIds(
            Object value,
            String fieldName,
            List<Map<String, Object>> payloads
    ) {
        List<String> persisted = requireStringList(value, fieldName);
        List<String> expected = idsOf(payloads, fieldName);
        if (!expected.equals(persisted)) {
            throw invalidField(fieldName, "必须与对应制品 ID 顺序完全一致");
        }
    }

    private static List<String> idsOf(
            List<Map<String, Object>> payloads,
            String fieldName
    ) {
        List<String> ids = payloads.stream()
                .map(payload -> requireText(payload.get("id"), fieldName + ".id"))
                .toList();
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw invalidField(fieldName, "ID 不能重复");
        }
        return ids;
    }

    private static List<String> requireCanonicalFileList(Object value, String fieldName) {
        List<String> values = requireStringList(value, fieldName);
        List<String> normalized = ChangePlan.normalizeFilePaths(values);
        if (!normalized.equals(values)) {
            throw invalidField(fieldName, "必须为去重后的规范相对路径");
        }
        return normalized;
    }

    private static List<String> requireStringList(Object value, String fieldName) {
        if (!(value instanceof List<?> values)) {
            throw invalidField(fieldName, "必须为字符串列表");
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw invalidField(fieldName, "只能包含非空字符串");
            }
            result.add(text.trim());
        }
        return List.copyOf(result);
    }

    private static List<Map<String, Object>> requireMapList(
            Object value,
            String fieldName,
            boolean requireId
    ) {
        if (!(value instanceof List<?> values)) {
            throw invalidField(fieldName, "必须为对象列表");
        }
        List<Map<String, Object>> result = new ArrayList<>(values.size());
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) {
                throw invalidField(fieldName, "必须为对象列表");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || entry.getValue() == null) {
                    throw invalidField(fieldName, "只能包含非空字符串键和非空值");
                }
                normalized.put(key, entry.getValue());
            }
            if (requireId) {
                requireText(normalized.get("id"), fieldName + ".id");
            }
            result.add(Map.copyOf(normalized));
        }
        return List.copyOf(result);
    }

    private static int requireNonNegativeInt(Object value, String fieldName) {
        if (!(value instanceof Number number)) {
            throw invalidField(fieldName, "必须为整数");
        }
        long longValue = number.longValue();
        if (longValue < 0L || longValue > Integer.MAX_VALUE
                || Double.compare(number.doubleValue(), longValue) != 0) {
            throw invalidField(fieldName, "必须为非负整数");
        }
        return (int) longValue;
    }

    private static boolean requireBoolean(Object value, String fieldName) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw invalidField(fieldName, "必须为布尔值");
    }

    private static String requireText(Object value, String fieldName) {
        String text = requireString(value, fieldName).trim();
        if (text.isBlank()) {
            throw invalidField(fieldName, "必须为非空字符串");
        }
        return text;
    }

    private static String requireExactText(
            Object value,
            String fieldName,
            String expected
    ) {
        String actual = requireText(value, fieldName);
        if (!expected.equals(actual)) {
            throw invalidField(fieldName, "不受支持: " + actual);
        }
        return actual;
    }

    private static String requireString(Object value, String fieldName) {
        if (value instanceof String text) {
            return text;
        }
        throw invalidField(fieldName, "必须为字符串");
    }

    private static IllegalArgumentException invalidField(String fieldName, String reason) {
        return new IllegalArgumentException("项目上下文制品字段 " + fieldName + ": " + reason);
    }

    /** 与仓库文件及语义索引直接相关的上下文。 */
    public record RepositoryContext(
            String intent,
            List<String> selectedFiles,
            int indexedFileCount,
            int indexedSymbolCount,
            List<Map<String, Object>> indexHits,
            String contextMode,
            String projectContext
    ) {

        public RepositoryContext {
            intent = requireText(intent, "intent");
            selectedFiles = requireCanonicalFileList(selectedFiles, "selectedFiles");
            indexedFileCount = requireNonNegativeInt(indexedFileCount, "indexedFileCount");
            indexedSymbolCount = requireNonNegativeInt(indexedSymbolCount, "indexedSymbolCount");
            indexHits = requireMapList(indexHits, "indexHits", false);
            contextMode = requireText(contextMode, "contextMode");
            projectContext = requireString(projectContext, "projectContext");
        }
    }

    /** 对不可信仓库文本完成隔离后的保护事实。 */
    public record ContextProtection(
            String digest,
            boolean secretsRedacted,
            boolean truncated,
            int sourceChars
    ) {

        public ContextProtection {
            digest = requireText(digest, "contextDigest");
            sourceChars = requireNonNegativeInt(sourceChars, "contextSourceChars");
        }
    }

    /** 供代码生成消费的记忆、recipe 与 skill 指引。 */
    public record AgentGuidance(
            String memoryContext,
            boolean hasGeneratedCode,
            List<Map<String, Object>> recipes,
            List<Map<String, Object>> skills
    ) {

        public AgentGuidance {
            memoryContext = requireString(memoryContext, "memoryContext");
            recipes = requireMapList(recipes, "recipes", true);
            skills = requireMapList(skills, "skills", true);
            idsOf(recipes, "recipes");
            idsOf(skills, "skills");
        }
    }
}
