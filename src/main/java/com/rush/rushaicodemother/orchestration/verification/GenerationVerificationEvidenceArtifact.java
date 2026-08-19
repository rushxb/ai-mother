package com.rush.rushaicodemother.orchestration.verification;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 已通过工程验证的强类型持久化制品。
 *
 * <p>该模块集中校验工程类型、验证步骤和详情结构。完成门禁与验证记录器必须通过
 * 此接口恢复事实，防止其他工程类型或损坏的检查点被误当成当前任务的通过证据。</p>
 */
public final class GenerationVerificationEvidenceArtifact {

    public static final String KEY = "verification_evidence";

    private static final String ROLE = "Verification";
    private static final String TITLE = "工程验证证据";
    private static final String PASSED_STATUS = "passed";

    private final GenerationValidationObservation observation;

    private GenerationVerificationEvidenceArtifact(
            GenerationValidationObservation observation) {
        this.observation = Objects.requireNonNull(observation, "验证观测不能为空");
    }

    /** 从验证器的实际通过结果创建规范制品，并拒绝写入错误工程类型的检查点。 */
    public static GenerationVerificationEvidenceArtifact fromObservation(
            GenerationValidationObservation observation,
            CodeGenTypeEnum expectedTargetType) {
        Objects.requireNonNull(observation, "验证观测不能为空");
        Objects.requireNonNull(expectedTargetType, "当前任务目标类型不能为空");
        if (observation.targetType() != expectedTargetType) {
            throw invalid("targetType", "与当前任务工程类型不一致");
        }
        return new GenerationVerificationEvidenceArtifact(observation);
    }

    /**
     * 从持久制品恢复验证事实，并要求证据工程类型与当前任务完全一致。
     *
     * <p>调用方可捕获 {@link IllegalArgumentException} 将损坏或陈旧证据视为不存在，
     * 但不得降级为一个部分可信的验证结果。</p>
     */
    public static GenerationVerificationEvidenceArtifact fromArtifact(
            GenerationArtifact artifact,
            CodeGenTypeEnum expectedTargetType) {
        if (artifact == null) {
            throw new IllegalArgumentException("验证证据制品不能为空");
        }
        if (expectedTargetType == null) {
            throw new IllegalArgumentException("当前任务目标类型不能为空");
        }
        if (!KEY.equals(artifact.key())) {
            throw invalid("key", "不是验证证据制品");
        }
        Map<String, Object> payload = artifact.payload();
        if (payload == null) {
            throw new IllegalArgumentException("验证证据载荷不能为空");
        }
        String status = requireText(payload.get("status"), "status");
        if (!PASSED_STATUS.equals(status)) {
            throw invalid("status", "必须为 passed");
        }
        CodeGenTypeEnum targetType = CodeGenTypeEnum.getEnumByValue(
                requireText(payload.get("targetType"), "targetType"));
        if (targetType == null) {
            throw invalid("targetType", "不是有效的工程类型");
        }
        if (targetType != expectedTargetType) {
            throw invalid("targetType", "与当前任务工程类型不一致");
        }
        String source = requireText(payload.get("source"), "source");
        EnumSet<GenerationExecutionPlan.ValidationStep> passedSteps =
                requirePassedSteps(payload.get("passedSteps"));
        Map<String, Object> details = requireDetails(payload.get("details"));
        return fromObservation(GenerationValidationObservation.passed(
                targetType, source, passedSteps, details), expectedTargetType);
    }

    /** 合并同一工程类型的后续实际观测；来源和详情以最新验证阶段为准。 */
    public GenerationVerificationEvidenceArtifact merge(
            GenerationValidationObservation latestObservation) {
        Objects.requireNonNull(latestObservation, "后续验证观测不能为空");
        if (observation.targetType() != latestObservation.targetType()) {
            throw invalid("targetType", "不能合并不同工程类型的验证结果");
        }
        EnumSet<GenerationExecutionPlan.ValidationStep> mergedSteps =
                EnumSet.copyOf(observation.passedSteps());
        mergedSteps.addAll(latestObservation.passedSteps());
        return new GenerationVerificationEvidenceArtifact(GenerationValidationObservation.passed(
                latestObservation.targetType(),
                latestObservation.source(),
                mergedSteps,
                latestObservation.details()));
    }

    /** 转换为可写入任务检查点的通用制品。 */
    public GenerationArtifact toArtifact() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", PASSED_STATUS);
        payload.put("source", observation.source());
        payload.put("targetType", observation.targetType().getValue());
        payload.put("passedSteps", observation.passedSteps().stream()
                .sorted()
                .map(Enum::name)
                .toList());
        payload.put("details", observation.details());
        return GenerationArtifact.of(KEY, ROLE, TITLE, payload);
    }

    public GenerationValidationObservation toObservation() {
        return observation;
    }

    private static EnumSet<GenerationExecutionPlan.ValidationStep> requirePassedSteps(
            Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw invalid("passedSteps", "必须为非空验证步骤列表");
        }
        EnumSet<GenerationExecutionPlan.ValidationStep> steps =
                EnumSet.noneOf(GenerationExecutionPlan.ValidationStep.class);
        for (Object item : values) {
            if (!(item instanceof String stepName)) {
                throw invalid("passedSteps", "只能包含验证步骤名称");
            }
            try {
                steps.add(GenerationExecutionPlan.ValidationStep.valueOf(stepName));
            } catch (IllegalArgumentException exception) {
                throw invalid("passedSteps", "包含未知验证步骤: " + stepName);
            }
        }
        return steps;
    }

    private static Map<String, Object> requireDetails(Object value) {
        if (!(value instanceof Map<?, ?> rawDetails)) {
            throw invalid("details", "必须为对象");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawDetails.entrySet()) {
            if (!(entry.getKey() instanceof String key) || entry.getValue() == null) {
                throw invalid("details", "只能包含字符串键和非空值");
            }
            details.put(key, entry.getValue());
        }
        return Map.copyOf(details);
    }

    private static String requireText(Object value, String fieldName) {
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        throw invalid(fieldName, "必须为非空字符串");
    }

    private static IllegalArgumentException invalid(String fieldName, String reason) {
        return new IllegalArgumentException("验证证据字段 " + fieldName + reason);
    }
}
