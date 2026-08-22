package com.rush.rushaicodemother.orchestration.verification;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;

import java.math.BigDecimal;
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
    private static final String SCHEMA_VERSION = "v2";
    private static final String PASSED_STATUS = "passed";

    private final VerificationSubject subject;
    private final GenerationValidationObservation observation;

    private GenerationVerificationEvidenceArtifact(
            VerificationSubject subject,
            GenerationValidationObservation observation) {
        this.subject = Objects.requireNonNull(subject, "验证证据主体不能为空");
        this.observation = Objects.requireNonNull(observation, "验证观测不能为空");
    }

    /** 从当前受租约保护的生成会话提取不可变验证主体。 */
    public static VerificationSubject currentSubject(
            GenerationPreparation preparation,
            GenerationSession session) {
        if (preparation == null || session == null) {
            throw new IllegalArgumentException("生成准备和会话不能为空");
        }
        GenerationExecutionContext context = session.executionContext();
        if (context == null) {
            throw new IllegalArgumentException("验证证据必须绑定生成执行上下文");
        }
        GenerationExecutionFence fence = context.executionFence();
        if (fence == null) {
            throw new IllegalArgumentException("验证证据必须绑定生成执行围栏");
        }
        if (!Objects.equals(preparation.taskId(), context.taskId())
                || !Objects.equals(context.taskId(), fence.taskId())) {
            throw invalid("taskId", "与当前生成执行上下文不一致");
        }
        return new VerificationSubject(
                requirePositive(context.appId(), "appId"),
                context.taskId(),
                fence.executionEpoch(),
                preparation.targetType()
        );
    }

    /** 从验证器的实际通过结果创建规范制品，并拒绝写入错误执行主体的检查点。 */
    public static GenerationVerificationEvidenceArtifact fromObservation(
            GenerationValidationObservation observation,
            VerificationSubject subject) {
        Objects.requireNonNull(observation, "验证观测不能为空");
        Objects.requireNonNull(subject, "当前验证主体不能为空");
        if (observation.targetType() != subject.targetType()) {
            throw invalid("targetType", "与当前任务工程类型不一致");
        }
        return new GenerationVerificationEvidenceArtifact(subject, observation);
    }

    /**
     * 从持久制品恢复验证事实，并要求证据工程类型与当前任务完全一致。
     *
     * <p>调用方可捕获 {@link IllegalArgumentException} 将损坏或陈旧证据视为不存在，
     * 但不得降级为一个部分可信的验证结果。</p>
     */
    public static GenerationVerificationEvidenceArtifact fromArtifact(
            GenerationArtifact artifact,
            VerificationSubject expectedSubject) {
        if (artifact == null) {
            throw new IllegalArgumentException("验证证据制品不能为空");
        }
        Objects.requireNonNull(expectedSubject, "当前验证主体不能为空");
        if (!KEY.equals(artifact.key())) {
            throw invalid("key", "不是验证证据制品");
        }
        Map<String, Object> payload = artifact.payload();
        if (payload == null) {
            throw new IllegalArgumentException("验证证据载荷不能为空");
        }
        String schemaVersion = requireText(payload.get("schemaVersion"), "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw invalid("schemaVersion", "仅支持 v2");
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
        VerificationSubject actualSubject = new VerificationSubject(
                requirePositive(payload.get("appId"), "appId"),
                requireText(payload.get("taskId"), "taskId"),
                requirePositive(payload.get("executionEpoch"), "executionEpoch"),
                targetType
        );
        if (!actualSubject.equals(expectedSubject)) {
            throw invalid("subject", "与当前生成执行主体不一致");
        }
        String source = requireText(payload.get("source"), "source");
        EnumSet<GenerationExecutionPlan.ValidationStep> passedSteps =
                requirePassedSteps(payload.get("passedSteps"));
        Map<String, Object> details = requireDetails(payload.get("details"));
        return fromObservation(GenerationValidationObservation.passed(
                targetType, source, passedSteps, details), expectedSubject);
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
        return new GenerationVerificationEvidenceArtifact(subject, GenerationValidationObservation.passed(
                latestObservation.targetType(),
                latestObservation.source(),
                mergedSteps,
                latestObservation.details()));
    }

    /** 转换为可写入任务检查点的通用制品。 */
    public GenerationArtifact toArtifact() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("status", PASSED_STATUS);
        payload.put("source", observation.source());
        payload.put("appId", subject.appId());
        payload.put("taskId", subject.taskId());
        payload.put("executionEpoch", subject.executionEpoch());
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

    private static long requirePositive(Object value, String fieldName) {
        if (!(value instanceof Number number)) {
            throw invalid(fieldName, "必须为正整数");
        }
        try {
            long result = new BigDecimal(number.toString()).longValueExact();
            if (result <= 0) {
                throw invalid(fieldName, "必须为正整数");
            }
            return result;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid(fieldName, "必须为正整数");
        }
    }

    private static IllegalArgumentException invalid(String fieldName, String reason) {
        return new IllegalArgumentException("验证证据字段 " + fieldName + reason);
    }

    /** 验证事实所归属的持久执行主体；任一维度变化都必须重新执行验证。 */
    public record VerificationSubject(
            long appId,
            String taskId,
            long executionEpoch,
            CodeGenTypeEnum targetType
    ) {

        public VerificationSubject {
            if (appId <= 0) {
                throw invalid("appId", "必须为正整数");
            }
            taskId = requireText(taskId, "taskId");
            if (executionEpoch <= 0) {
                throw invalid("executionEpoch", "必须为正整数");
            }
            Objects.requireNonNull(targetType, "验证证据工程类型不能为空");
        }
    }
}
