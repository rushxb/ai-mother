package com.rush.rushaicodemother.orchestration.artifact;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 模板初始化制品的强类型投影。
 *
 * <p>持久检查点仍使用通用 {@link GenerationArtifact}，本模块集中拥有稳定字段名、
 * 类型恢复与生产者扩展字段保留规则，避免模板节点和代码节点各自解析动态 Map。</p>
 */
public final class TemplateBootstrapArtifact {

    public static final String KEY = "template_bootstrap";

    private static final String ROLE = "Template";

    private final boolean bootstrapped;
    private final String templateId;
    private final String projectPath;
    private final int fileCount;
    private final CodeGenTypeEnum targetType;
    private final String reason;
    private final Map<String, Object> payload;

    private TemplateBootstrapArtifact(boolean bootstrapped,
                                      String templateId,
                                      String projectPath,
                                      int fileCount,
                                      CodeGenTypeEnum targetType,
                                      String reason,
                                      Map<String, Object> payload) {
        this.bootstrapped = bootstrapped;
        this.templateId = templateId;
        this.projectPath = projectPath;
        this.fileCount = fileCount;
        this.targetType = targetType;
        this.reason = reason;
        this.payload = payload;
    }

    /** 从持久化载荷恢复并校验模板初始化事实。 */
    public static TemplateBootstrapArtifact fromPayload(Map<String, Object> source) {
        return fromPayload(source, null);
    }

    /** 从载荷恢复事实；历史载荷缺少目标类型时使用 DAG 当前类型补齐。 */
    public static TemplateBootstrapArtifact fromPayload(Map<String, Object> source,
                                                        CodeGenTypeEnum fallbackTargetType) {
        Objects.requireNonNull(source, "模板初始化制品载荷不能为空");
        boolean bootstrapped = requireBoolean(source.get("bootstrapped"), "bootstrapped");
        String templateId = requireString(source.get("templateId"), "templateId");
        String projectPath = requireString(source.get("projectPath"), "projectPath");
        int fileCount = requireNonNegativeInteger(source.get("fileCount"), "fileCount");
        String targetTypeValue = source.containsKey("targetType")
                ? requireString(source.get("targetType"), "targetType")
                : "";
        CodeGenTypeEnum targetType = targetTypeValue.isBlank()
                ? fallbackTargetType
                : CodeGenTypeEnum.getEnumByValue(targetTypeValue);
        if (!targetTypeValue.isBlank() && targetType == null) {
            throw new IllegalArgumentException("模板初始化制品 targetType 无效: " + targetTypeValue);
        }
        if (targetType != null && fallbackTargetType != null
                && targetType != fallbackTargetType) {
            throw new IllegalArgumentException(
                    "模板初始化制品 targetType 与当前 DAG 类型不一致: "
                            + targetType + " != " + fallbackTargetType);
        }
        String reason = requireString(source.get("reason"), "reason");

        Map<String, Object> normalizedPayload = new LinkedHashMap<>(source);
        normalizedPayload.put("bootstrapped", bootstrapped);
        normalizedPayload.put("templateId", templateId);
        normalizedPayload.put("projectPath", projectPath);
        normalizedPayload.put("fileCount", fileCount);
        normalizedPayload.put("targetType", targetType == null ? "" : targetType.getValue());
        normalizedPayload.put("reason", reason);
        return new TemplateBootstrapArtifact(
                bootstrapped,
                templateId,
                projectPath,
                fileCount,
                targetType,
                reason,
                Map.copyOf(normalizedPayload)
        );
    }

    /** 从通用持久制品恢复强类型事实，并拒绝误传的其他制品。 */
    public static TemplateBootstrapArtifact fromArtifact(GenerationArtifact artifact) {
        return fromArtifact(artifact, null);
    }

    /** 从通用制品恢复，并为历史检查点补齐 DAG 当前目标类型。 */
    public static TemplateBootstrapArtifact fromArtifact(GenerationArtifact artifact,
                                                         CodeGenTypeEnum fallbackTargetType) {
        Objects.requireNonNull(artifact, "模板初始化制品不能为空");
        if (!KEY.equals(artifact.key())) {
            throw new IllegalArgumentException("制品类型不匹配，期望: " + KEY + "，实际: " + artifact.key());
        }
        return fromPayload(artifact.payload(), fallbackTargetType);
    }

    /** 为不需要模板或缺少目标类型的路径生成同结构诊断制品。 */
    public static TemplateBootstrapArtifact skipped(CodeGenTypeEnum targetType, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("跳过模板初始化的原因不能为空");
        }
        return fromPayload(Map.of(
                "bootstrapped", false,
                "templateId", "",
                "projectPath", "",
                "fileCount", 0,
                "targetType", targetType == null ? "" : targetType.getValue(),
                "reason", reason.trim()
        ));
    }

    /** 转换为可持久化、可恢复的通用制品。 */
    public GenerationArtifact toArtifact(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("模板初始化制品标题不能为空");
        }
        return GenerationArtifact.of(KEY, ROLE, title.trim(), payload);
    }

    public boolean bootstrapped() {
        return bootstrapped;
    }

    public String templateId() {
        return templateId;
    }

    public String projectPath() {
        return projectPath;
    }

    public int fileCount() {
        return fileCount;
    }

    public CodeGenTypeEnum targetType() {
        return targetType;
    }

    public String reason() {
        return reason;
    }

    private static boolean requireBoolean(Object value, String fieldName) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        throw invalidField(fieldName, "必须为布尔值");
    }

    private static String requireString(Object value, String fieldName) {
        if (value instanceof String text) {
            return text.trim();
        }
        throw invalidField(fieldName, "必须为字符串");
    }

    private static int requireNonNegativeInteger(Object value, String fieldName) {
        if (!(value instanceof Number number)) {
            throw invalidField(fieldName, "必须为整数");
        }
        long longValue = number.longValue();
        if (number.doubleValue() != longValue || longValue < 0 || longValue > Integer.MAX_VALUE) {
            throw invalidField(fieldName, "必须为非负整数");
        }
        return (int) longValue;
    }

    private static IllegalArgumentException invalidField(String fieldName, String reason) {
        return new IllegalArgumentException("模板初始化制品字段 " + fieldName + reason);
    }
}
