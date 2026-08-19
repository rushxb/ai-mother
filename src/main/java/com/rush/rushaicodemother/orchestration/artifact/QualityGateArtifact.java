package com.rush.rushaicodemother.orchestration.artifact;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 可持久恢复的质量门禁事实模型。
 *
 * <p>该模块集中拥有门禁字段的类型恢复与状态不变量，防止检查点中的
 * {@code passed}、{@code level} 和阻断项互相矛盾时绕过 Review。</p>
 */
public final class QualityGateArtifact {

    public static final String KEY = "quality_gate";

    private static final Set<String> CORE_FIELDS = Set.of(
            "passed", "level", "blockers", "warnings", "passes");

    private final QualityGateResult result;
    private final Map<String, Object> payload;

    private QualityGateArtifact(QualityGateResult result, Map<String, Object> payload) {
        this.result = result;
        this.payload = Map.copyOf(payload);
    }

    /** 从 Review 结果创建可持久化门禁事实，并保留非核心诊断详情。 */
    public static QualityGateArtifact fromResult(QualityGateResult result,
                                                 Map<String, Object> additionalPayload) {
        validateResult(Objects.requireNonNull(result, "质量门禁结果不能为空"));
        Map<String, Object> details = additionalPayload == null ? Map.of() : additionalPayload;
        for (String fieldName : CORE_FIELDS) {
            if (details.containsKey(fieldName)) {
                throw invalid("核心字段 " + fieldName + " 只能由质量门禁模块写入");
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.put("passed", result.passed());
        payload.put("level", result.level());
        payload.put("blockers", result.blockers());
        payload.put("warnings", result.warnings());
        payload.put("passes", result.passes());
        return new QualityGateArtifact(result, payload);
    }

    /** 从持久制品严格恢复门禁结果。 */
    public static QualityGateArtifact fromArtifact(GenerationArtifact artifact) {
        Objects.requireNonNull(artifact, "质量门禁制品不能为空");
        if (!KEY.equals(artifact.key())) {
            throw invalid("制品类型不匹配: " + artifact.key());
        }
        Map<String, Object> payload = Objects.requireNonNull(
                artifact.payload(), "质量门禁制品载荷不能为空");
        boolean passed = requireBoolean(payload.get("passed"), "passed");
        String level = requireText(payload.get("level"), "level");
        List<String> blockers = requireStringList(payload.get("blockers"), "blockers");
        List<String> warnings = requireStringList(payload.get("warnings"), "warnings");
        List<String> passes = requireStringList(payload.get("passes"), "passes");

        if (passed) {
            String expectedLevel = warnings.isEmpty() ? "pass" : "warning";
            if (!blockers.isEmpty()) {
                throw invalid("通过状态不能包含阻断项");
            }
            if (!expectedLevel.equals(level)) {
                throw invalid("通过状态与 level 不一致: " + level);
            }
            QualityGateResult result = QualityGateResult.passed(warnings, passes);
            return new QualityGateArtifact(result, payload);
        }
        if (blockers.isEmpty()) {
            throw invalid("阻断状态必须包含阻断项");
        }
        if (!"blocker".equals(level)) {
            throw invalid("阻断状态与 level 不一致: " + level);
        }
        QualityGateResult result = QualityGateResult.failed(blockers, warnings, passes);
        return new QualityGateArtifact(result, payload);
    }

    public QualityGateResult result() {
        return result;
    }

    /** 转换为通用持久制品。 */
    public GenerationArtifact toArtifact(String role, String title) {
        return GenerationArtifact.of(
                KEY,
                requireText(role, "role"),
                requireText(title, "title"),
                payload
        );
    }

    private static void validateResult(QualityGateResult result) {
        Objects.requireNonNull(result.blockers(), "质量门禁 blockers 不能为空");
        Objects.requireNonNull(result.warnings(), "质量门禁 warnings 不能为空");
        Objects.requireNonNull(result.passes(), "质量门禁 passes 不能为空");
        validateMessages(result.blockers(), "blockers");
        validateMessages(result.warnings(), "warnings");
        validateMessages(result.passes(), "passes");
        String expectedLevel;
        if (result.passed()) {
            if (!result.blockers().isEmpty()) {
                throw invalid("通过状态不能包含阻断项");
            }
            expectedLevel = result.warnings().isEmpty() ? "pass" : "warning";
        } else {
            if (result.blockers().isEmpty()) {
                throw invalid("阻断状态必须包含阻断项");
            }
            expectedLevel = "blocker";
        }
        if (!expectedLevel.equals(result.level())) {
            throw invalid("门禁状态与 level 不一致: " + result.level());
        }
    }

    private static void validateMessages(List<String> messages, String fieldName) {
        if (messages.stream().anyMatch(message -> message == null || message.isBlank())) {
            throw invalid("字段 " + fieldName + " 只能包含非空字符串");
        }
    }

    private static boolean requireBoolean(Object value, String fieldName) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw invalid("字段 " + fieldName + " 必须为布尔值");
    }

    private static String requireText(Object value, String fieldName) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw invalid("字段 " + fieldName + " 必须为非空字符串");
    }

    private static List<String> requireStringList(Object value, String fieldName) {
        if (!(value instanceof Collection<?> collection)) {
            throw invalid("字段 " + fieldName + " 必须为字符串列表");
        }
        for (Object item : collection) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw invalid("字段 " + fieldName + " 只能包含非空字符串");
            }
        }
        return collection.stream().map(String.class::cast).toList();
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("质量门禁制品已损坏，" + reason);
    }
}
