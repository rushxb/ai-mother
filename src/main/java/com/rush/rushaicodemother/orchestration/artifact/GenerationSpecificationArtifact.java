package com.rush.rushaicodemother.orchestration.artifact;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 代码生成规范的强类型事实模型。
 *
 * <p>持久检查点仍使用通用 {@link GenerationArtifact}，本模块集中拥有核心布尔事实的
 * 类型恢复和派生模式一致性，避免 Review、BuildFix 与执行链分别解释动态 Map。</p>
 */
public final class GenerationSpecificationArtifact {

    public static final String KEY = "generation_spec";

    private static final Set<String> CORE_FIELDS = Set.of(
            "enhancedPrompt",
            "patchFirst",
            "requiresBuild",
            "validationMode",
            "generationMode",
            "artifactMode"
    );

    private final String enhancedPrompt;
    private final boolean patchFirst;
    private final boolean requiresBuild;
    private final String validationMode;
    private final String generationMode;
    private final String artifactMode;
    private final Map<String, Object> payload;

    private GenerationSpecificationArtifact(String enhancedPrompt,
                                            boolean promptDeclared,
                                            boolean patchFirst,
                                            boolean requiresBuild,
                                            Map<String, Object> sourcePayload) {
        this.enhancedPrompt = enhancedPrompt;
        this.patchFirst = patchFirst;
        this.requiresBuild = requiresBuild;
        this.validationMode = requiresBuild ? "build_validation" : "review_only";
        this.generationMode = patchFirst ? "patch_first_update" : "full_generation";
        this.artifactMode = patchFirst ? "patch_plan" : "generation_plan";
        Map<String, Object> normalizedPayload = new LinkedHashMap<>(sourcePayload);
        if (promptDeclared) {
            normalizedPayload.put("enhancedPrompt", enhancedPrompt);
        }
        normalizedPayload.put("patchFirst", patchFirst);
        normalizedPayload.put("requiresBuild", requiresBuild);
        normalizedPayload.put("validationMode", validationMode);
        normalizedPayload.put("generationMode", generationMode);
        normalizedPayload.put("artifactMode", artifactMode);
        this.payload = Map.copyOf(normalizedPayload);
    }

    /** 创建供规划链和执行链消费的完整代码生成规范。 */
    public static GenerationSpecificationArtifact execution(String enhancedPrompt,
                                                            boolean patchFirst,
                                                            boolean requiresBuild,
                                                            Map<String, Object> additionalPayload) {
        String normalizedPrompt = requireNonBlankText(enhancedPrompt, "enhancedPrompt");
        Map<String, Object> details = additionalPayload == null ? Map.of() : additionalPayload;
        for (String fieldName : CORE_FIELDS) {
            if (details.containsKey(fieldName)) {
                throw invalidField(fieldName, "只能由生成规范模块写入");
            }
        }
        return new GenerationSpecificationArtifact(
                normalizedPrompt,
                true,
                patchFirst,
                requiresBuild,
                details
        );
    }

    /** 创建 CREATE 模板落盘后仅用于验证和修复的规范，不伪造模型执行意图。 */
    public static GenerationSpecificationArtifact postGenerationValidation(
            boolean requiresBuild) {
        return new GenerationSpecificationArtifact(
                "",
                false,
                false,
                requiresBuild,
                Map.of()
        );
    }

    /** 从通用持久制品恢复生成规范，并拒绝类型损坏或派生字段矛盾的检查点。 */
    public static GenerationSpecificationArtifact fromArtifact(GenerationArtifact artifact) {
        Objects.requireNonNull(artifact, "生成规范制品不能为空");
        if (!KEY.equals(artifact.key())) {
            throw new IllegalArgumentException(
                    "制品类型不匹配，期望: " + KEY + "，实际: " + artifact.key());
        }
        Map<String, Object> payload = Objects.requireNonNull(
                artifact.payload(), "生成规范制品载荷不能为空");
        boolean promptDeclared = payload.containsKey("enhancedPrompt");
        String enhancedPrompt = promptDeclared
                ? requireNonBlankText(payload.get("enhancedPrompt"), "enhancedPrompt")
                : "";
        GenerationSpecificationArtifact restored = new GenerationSpecificationArtifact(
                enhancedPrompt,
                promptDeclared,
                requireBoolean(payload.get("patchFirst"), "patchFirst"),
                requireBoolean(payload.get("requiresBuild"), "requiresBuild"),
                payload
        );
        validateDerivedField(payload, "validationMode", restored.validationMode);
        validateDerivedField(payload, "generationMode", restored.generationMode);
        validateDerivedField(payload, "artifactMode", restored.artifactMode);
        return restored;
    }

    /**
     * 读取构建要求；旧检查点缺失或损坏时由调用方明确选择 fail-safe 默认值。
     *
     * <p>这个兼容 interface 仅用于决定“是否执行更严格的构建验证”；
     * 规划、Review 与 BuildFix 仍必须使用严格恢复，不允许损坏事实继续执行。</p>
     */
    public static boolean requiresBuildOrDefault(GenerationArtifact artifact,
                                                 boolean defaultValue) {
        if (artifact == null) {
            return defaultValue;
        }
        if (!KEY.equals(artifact.key()) || artifact.payload() == null) {
            return defaultValue;
        }
        Object requiresBuild = artifact.payload().get("requiresBuild");
        return requiresBuild instanceof Boolean buildRequired
                ? buildRequired
                : defaultValue;
    }

    /** 转换为可持久、可恢复的通用制品。 */
    public GenerationArtifact toArtifact(String role, String title) {
        return GenerationArtifact.of(
                KEY,
                requireNonBlankText(role, "role"),
                requireNonBlankText(title, "title"),
                payload
        );
    }

    public String enhancedPrompt() {
        return enhancedPrompt;
    }

    /** 返回该制品是否包含可交给模型执行的非空提示词。 */
    public boolean hasExecutionPrompt() {
        return !enhancedPrompt.isBlank();
    }

    /** 只有完整执行规范才能作为“意图已覆盖”完成证据。 */
    public boolean provesIntentCoverage() {
        return hasExecutionPrompt();
    }

    public boolean patchFirst() {
        return patchFirst;
    }

    public boolean requiresBuild() {
        return requiresBuild;
    }

    public String validationMode() {
        return validationMode;
    }

    public String generationMode() {
        return generationMode;
    }

    public String artifactMode() {
        return artifactMode;
    }

    private static boolean requireBoolean(Object value, String fieldName) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw invalidField(fieldName, "必须为布尔值");
    }

    private static String requireNonBlankText(Object value, String fieldName) {
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        throw invalidField(fieldName, "必须为非空字符串");
    }

    private static void validateDerivedField(Map<String, Object> payload,
                                             String fieldName,
                                             String expectedValue) {
        if (!payload.containsKey(fieldName)) {
            return;
        }
        Object value = payload.get(fieldName);
        if (!(value instanceof String persistedValue)) {
            throw invalidField(fieldName, "必须为字符串");
        }
        if (!expectedValue.equals(persistedValue)) {
            throw invalidField(
                    fieldName,
                    "与源事实不一致: " + persistedValue + " != " + expectedValue);
        }
    }

    private static IllegalArgumentException invalidField(String fieldName, String reason) {
        return new IllegalArgumentException("生成规范制品字段 " + fieldName + reason);
    }
}
